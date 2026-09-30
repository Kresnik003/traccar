/*
 * Copyright 2026 Kresnik003 (juanilloferrol@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.seguridad;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.EnlacesDirectos;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.Fijacion;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Guardia;
import org.traccar.model.Position;
import org.traccar.notification.VentanaAccidente;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.telegram.ClienteTelegram;
import org.traccar.telegram.FirmaBoton;
import org.traccar.telegram.ReceptorBoton;
import org.traccar.telegram.RespuestaTelegram;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The crash guard (improvement 114). A crash while riding asks the rider on
 * Telegram whether they are fine; if nobody answers in two minutes and the rider
 * has not ridden on, the emergency contacts and the people of the ride in
 * progress are warned, with a live link.
 *
 * alEvento runs on the Netty thread while the event is being saved: it never
 * throws, and it never waits on Telegram - every message leaves through an
 * executor (found in review on 2026-09-29: a slow Telegram held every device).
 * The two minutes are counted by TaskGuardias, which reads the guards from the
 * database, so a restart does not lose one; and a warning going out is kept
 * there too (AVISANDO), so a restart does not lose the recipients still
 * waiting for it. Nothing is armed while the bot's inbox is off: nobody could
 * press the button.
 */
@Singleton
public class GuardianGolpes implements ReceptorBoton {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardianGolpes.class);

    /** Retries of a warning that did not go out: 30 s, 2 min, 10 min. */
    static final long[] ESPERAS_REINTENTO = {30_000, 120_000, 600_000};

    /**
     * Retries of the question to the rider. They have to fit in the two minutes:
     * a question that never arrives means the family is told about somebody who
     * is fine (found in review on 2026-09-30: it was sent once, and a 429 or a
     * slow Telegram left the rider without the button). The countdown looks
     * every 10 s, so these are at the next pass after each wait.
     */
    static final long[] ESPERAS_PREGUNTA = {5_000, 15_000, 30_000};

    /** How long the link sent after a crash lets people follow the bike. */
    static final long DURACION_ENLACE = 24 * 3_600_000L;

    /** Frames of the same crash keep arriving for minutes: one guard per crash. */
    private static final long MISMO_GOLPE = 10 * 60_000L;

    /**
     * Positions are read by fix time, which has an index, with this much room:
     * a frame without a fix carries the fix time of its last good position. The
     * device time decides in memory, in ReglasGolpe.
     */
    static final long MARGEN_FIJACION = 30 * 60_000L;

    private static final int POSICIONES_MAXIMAS = 500;

    /**
     * The countdown looks every 10 s: a guard still waiting a minute past its
     * limit was missed, and nobody has been warned about that crash.
     */
    static final long VENCIDA = 60_000;

    /**
     * A warning still going out this long after its limit is stuck: the retries
     * take 12.5 minutes, and whoever is still missing it has not been told.
     */
    static final long ATASCADA = 15 * 60_000L;

    /**
     * The overdue count is the one of the last pass. Older than this, it is not
     * published at all: the countdown has stopped, and a stale zero would say
     * all is well (CuentaAtrasParada says the rest).
     */
    static final long FRESCURA = 60_000;

    private record Pendiente(
            long guardiaId, String chatId, String texto, List<ClienteTelegram.Boton> botones,
            Long contactoId, int intento, long cuando, boolean pregunta) {

        Pendiente otraVez(long ahora, long[] esperas) {
            return new Pendiente(guardiaId, chatId, texto, botones, contactoId, intento + 1,
                    ahora + esperas[intento], pregunta);
        }
    }

    private record Recuento(int vencidas, long cuando) {
    }

    private final boolean activo;
    private final Storage storage;
    private final ClienteTelegram cliente;
    private final EnlacesDirectos enlaces;
    private final Destinatarios destinatarios;
    private final FirmaBoton firma;
    private final Executor envios;
    private final List<Pendiente> pendientes = new ArrayList<>();
    /** Warnings going out from this process: guard → chats not served yet. Guarded by this. */
    private final Map<Long, Set<String>> enCurso = new HashMap<>();
    private final AtomicLong avisosPerdidos = new AtomicLong();
    private final AtomicLong preguntasFallidas = new AtomicLong();
    private volatile long ultimaRevision;
    private volatile Recuento recuento;

    @Inject
    public GuardianGolpes(
            Config config, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios, ExecutorService executorService) {
        this(config.getBoolean(Keys.TELEGRAM_BUZON), storage, cliente, enlaces, destinatarios,
                new FirmaBoton(config.getString(Keys.NOTIFICATOR_TELEGRAM_KEY, "")), executorService);
    }

    GuardianGolpes(
            boolean activo, Storage storage, ClienteTelegram cliente, EnlacesDirectos enlaces,
            Destinatarios destinatarios, FirmaBoton firma, Executor envios) {
        this.activo = activo;
        this.storage = storage;
        this.cliente = cliente;
        this.enlaces = enlaces;
        this.destinatarios = destinatarios;
        this.firma = firma;
        this.envios = envios;
    }

    @Override
    public String accion() {
        return "g";
    }

    public boolean activo() {
        return activo;
    }

    /** When the countdown last read its guards, or zero if it never did (VigiaSeguridad). */
    public long ultimaRevision() {
        return ultimaRevision;
    }

    /** Warnings given up after every retry (VigiaSeguridad). */
    public long avisosPerdidos() {
        return avisosPerdidos.get();
    }

    /** Questions to the rider that never reached them, retries included (VigiaSeguridad). */
    public long preguntasFallidas() {
        return preguntasFallidas.get();
    }

    /**
     * Guards the countdown missed, as counted by its last pass: waiting more than
     * {@link #VENCIDA} past their limit, or still warning more than
     * {@link #ATASCADA} past it. For VigiaSeguridad.
     *
     * It does not ask the database. Found in review on 2026-09-30: counting them
     * in every scrape made /api/metrics wait on the database, so with MySQL hung
     * the whole scrape failed and the alerts meant for "server alive, database
     * down" could never fire. Empty, never zero, before the first pass or when
     * the last one is old: a zero there would tell the watchdog all is well.
     */
    public OptionalInt vencidas(long ahora) {
        Recuento ultimo = recuento;
        if (ultimo == null || ahora - ultimo.cuando() > FRESCURA) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(ultimo.vencidas());
    }

    public void alEvento(Event event, Position position) {
        if (!activo) {
            return;
        }
        try {
            if (position == null || !VentanaAccidente.esAccidente(event)) {
                return;
            }
            long ahora = System.currentTimeMillis();
            long deviceId = event.getDeviceId();
            Date golpe = position.getDeviceTime();
            if (golpe == null || !ReglasGolpe.reciente(golpe, ahora) || mismoGolpe(deviceId, golpe)) {
                return;
            }
            List<Position> previas = new ArrayList<>(posiciones(deviceId,
                    new Date(golpe.getTime() - ReglasGolpe.ESPERA - MARGEN_FIJACION), golpe));
            // The crash frame itself: with the records sent in batches it may be the
            // only proof of riding, and a frame without a fix is outside the window.
            previas.add(position);
            if (!ReglasGolpe.ibaEnMarcha(previas, golpe)) {
                return;
            }
            // No rider chosen, or nobody to warn: nothing is armed, and nobody is
            // promised a warning that would not happen.
            if (destinatarios.motorista(deviceId).isEmpty() || destinatarios.de(deviceId).isEmpty()) {
                return;
            }
            Guardia guardia = new Guardia();
            guardia.setDeviceId(deviceId);
            guardia.setEventId(event.getId());
            guardia.setPositionId(position.getId());
            guardia.setGolpe(golpe);
            guardia.setLimite(ReglasGolpe.limite(golpe, ahora));
            if (Fijacion.tiene(position)) {
                guardia.setLatitud(position.getLatitude());
                guardia.setLongitud(position.getLongitude());
            }
            guardia.setEstado(Guardia.ESPERANDO);
            guardia.setId(storage.addObject(guardia, new Request(new Columns.Exclude("id"))));
            Optional<String> chat = destinatarios.chatDelMotorista(deviceId);
            if (chat.isPresent()) {
                List<ClienteTelegram.Boton> boton = List.of(
                        new ClienteTelegram.Boton("Estoy bien", firma.firmar(accion(), guardia.getId())));
                Pendiente pregunta = new Pendiente(guardia.getId(), chat.get(),
                        Mensajes.alDuenoTrasGolpe(moto(deviceId)), boton, null, 0, ahora, true);
                envios.execute(() -> enviar(pregunta, ahora));
            }
        } catch (Exception e) {
            LOGGER.warn("Crash guard failed for device {}", event.getDeviceId(), e);
        }
    }

    /** A guard of this bike still waiting, or opened by a crash of the last few minutes. */
    private boolean mismoGolpe(long deviceId, Date golpe) throws StorageException {
        for (Guardia guardia : storage.getObjects(Guardia.class, new Request(new Columns.All(),
                new Condition.Equals("deviceid", deviceId), new Order("golpe", true, 5)))) {
            if (Guardia.ESPERANDO.equals(guardia.getEstado())
                    || Math.abs(golpe.getTime() - guardia.getGolpe().getTime()) < MISMO_GOLPE) {
                return true;
            }
        }
        return false;
    }

    /**
     * The button and the countdown change the same guard from two threads: both
     * go through this lock and read the guard again inside, so "I'm fine" and
     * "the contacts were warned" can never both be true (found in review).
     */
    @Override
    public synchronized String pulsado(long id, String chatId) {
        try {
            Guardia guardia = leer(id);
            if (guardia == null) {
                return "Este aviso ya no existe.";
            }
            if (!chatId.equals(destinatarios.chatDelMotorista(guardia.getDeviceId()).orElse(null))) {
                return "Este botón no es tuyo.";
            }
            if (Guardia.AVISADA.equals(guardia.getEstado()) || Guardia.AVISANDO.equals(guardia.getEstado())) {
                return "Tus contactos ya han sido avisados. Diles tú que estás bien.";
            }
            if (!Guardia.ESPERANDO.equals(guardia.getEstado())) {
                return "Ya estaba cancelado: no avisaremos a nadie.";
            }
            guardia.setEstado(Guardia.CANCELADA_BOTON);
            actualizar(guardia);
            return "Perfecto: no avisaremos a nadie.";
        } catch (StorageException e) {
            LOGGER.warn("Crash guard button failed", e);
            return "No se ha podido guardar. Vuelve a pulsar.";
        }
    }

    /** Called every few seconds by TaskGuardias. */
    public void revisar(long ahora) {
        if (!activo) {
            return;
        }
        try {
            int vencidas = 0;
            for (Guardia guardia : storage.getObjects(Guardia.class, new Request(new Columns.All(),
                    new Condition.Or(
                            new Condition.Equals("estado", Guardia.ESPERANDO),
                            new Condition.Equals("estado", Guardia.AVISANDO))))) {
                Guardia tras = guardia;
                try {
                    tras = revisar(guardia.getId(), ahora);
                } catch (Exception e) {
                    LOGGER.warn("Crash guard {} failed", guardia.getId(), e);
                }
                if (tras != null && vencida(tras, ahora)) {
                    vencidas++;
                }
            }
            recuento = new Recuento(vencidas, ahora);
            ultimaRevision = ahora;
        } catch (StorageException e) {
            LOGGER.warn("Crash guards could not be read", e);
        }
        reintentar(ahora);
    }

    /** Missed by the countdown, as it stands after this pass looked at it. */
    private static boolean vencida(Guardia guardia, long ahora) {
        // One without a limit can never be reviewed: exactly what must be seen.
        if (Guardia.ESPERANDO.equals(guardia.getEstado())) {
            return guardia.getLimite() == null || guardia.getLimite().getTime() < ahora - VENCIDA;
        }
        if (Guardia.AVISANDO.equals(guardia.getEstado())) {
            return guardia.getLimite() == null || guardia.getLimite().getTime() < ahora - ATASCADA;
        }
        return false;
    }

    /** One guard, read again under the lock; returns it as it is left. */
    private synchronized Guardia revisar(long id, long ahora) throws StorageException {
        Guardia guardia = leer(id);
        if (guardia == null) {
            return null;
        }
        if (Guardia.AVISANDO.equals(guardia.getEstado())) {
            // Going out from this process already, or left half sent by a restart.
            if (!enCurso.containsKey(id)) {
                enviarA(guardia, mensajes(guardia, posiciones(guardia, ahora),
                        destinatarios.de(guardia.getDeviceId())), ahora);
            }
            return guardia;
        }
        if (!Guardia.ESPERANDO.equals(guardia.getEstado())) {
            return guardia;
        }
        List<Position> todas = posiciones(guardia, ahora);
        if (ReglasGolpe.seHaMovido(guardia.getGolpe(), despues(guardia, todas))) {
            guardia.setEstado(Guardia.CANCELADA_MOVIMIENTO);
            actualizar(guardia);
        } else if (ahora >= guardia.getLimite().getTime()) {
            avisar(guardia, todas, ahora);
        }
        return guardia;
    }

    /**
     * Whom to warn and what to say is worked out FIRST, then the guard is marked
     * as warning, then the messages go out. Found in review: marking it first and
     * failing to read the recipients left a guard "warned" that warned nobody.
     *
     * Found in review on 2026-09-30: it was marked AVISADA before sending, and
     * the retries lived only in memory, so a restart during them lost the
     * warning and nothing counted it. Now it stays AVISANDO, with the chats
     * already served, until every recipient is: a restart sends the rest.
     */
    private void avisar(Guardia guardia, List<Position> todas, long ahora) throws StorageException {
        List<Destinatarios.Destinatario> a = destinatarios.de(guardia.getDeviceId());
        var enlace = enlaces.enlaceDeEquipo(guardia.getDeviceId(),
                new Date(guardia.getGolpe().getTime() + DURACION_ENLACE), "golpe", guardia.getId());
        guardia.setEnlace(enlace.map(EnlacesDirectos.Enlace::url).orElse(null));
        Map<String, Pendiente> mensajes = mensajes(guardia, todas, a);
        guardia.setEstado(Guardia.AVISANDO);
        guardia.setResueltos(",");
        actualizar(guardia);
        enviarA(guardia, mensajes, ahora);
    }

    /** The warning for each recipient of the guard, by chat. */
    private Map<String, Pendiente> mensajes(
            Guardia guardia, List<Position> todas, List<Destinatarios.Destinatario> a) throws StorageException {
        long deviceId = guardia.getDeviceId();
        String persona = destinatarios.persona(deviceId);
        String moto = moto(deviceId);
        Position delGolpe = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.Equals("id", guardia.getPositionId())));
        String lugar = ReglasGolpe.dondeEsta(delGolpe, despues(guardia, todas))
                .map(p -> Mensajes.lugar(p.getAddress(), p.getLatitude(), p.getLongitude()))
                .or(() -> ReglasGolpe.ultimaAntes(todas, guardia.getGolpe())
                        .map(p -> Mensajes.lugarAntesDelGolpe(p.getAddress(), p.getLatitude(), p.getLongitude())))
                .orElse(Mensajes.lugarDesconocido(guardia.getEnlace() != null));
        Map<String, Pendiente> mensajes = new LinkedHashMap<>();
        for (Destinatarios.Destinatario destinatario : a) {
            String texto = destinatario.quedada() == null
                    ? Mensajes.aContacto(persona, moto, guardia.getGolpe(), lugar, guardia.getEnlace())
                    : Mensajes.aCompanero(destinatario.quedada(), persona, moto, guardia.getGolpe(), lugar,
                            guardia.getEnlace());
            mensajes.putIfAbsent(destinatario.chatId(), new Pendiente(guardia.getId(), destinatario.chatId(),
                    texto, List.of(), destinatario.contactoId(), 0, 0, false));
        }
        return mensajes;
    }

    /** Sends the warning to every recipient not served yet; with none left, it is warned. */
    private void enviarA(Guardia guardia, Map<String, Pendiente> mensajes, long ahora) throws StorageException {
        Set<String> faltan = new LinkedHashSet<>();
        for (String chat : mensajes.keySet()) {
            if (!resuelto(guardia, chat)) {
                faltan.add(chat);
            }
        }
        if (faltan.isEmpty()) {
            guardia.setEstado(Guardia.AVISADA);
            actualizar(guardia);
            return;
        }
        enCurso.put(guardia.getId(), faltan);
        for (String chat : List.copyOf(faltan)) {
            Pendiente pendiente = mensajes.get(chat);
            envios.execute(() -> enviar(pendiente, ahora));
        }
    }

    private static boolean resuelto(Guardia guardia, String chat) {
        return guardia.getResueltos() != null && guardia.getResueltos().contains("," + chat + ",");
    }

    private void enviar(Pendiente pendiente, long ahora) {
        RespuestaTelegram respuesta;
        try {
            respuesta = cliente.enviar(pendiente.chatId(), pendiente.texto(), pendiente.botones());
        } catch (RuntimeException e) {
            respuesta = new RespuestaTelegram(0, e.getMessage());
        }
        if (respuesta.ok()) {
            resolver(pendiente);
            return;
        }
        long[] esperas = pendiente.pregunta() ? ESPERAS_PREGUNTA : ESPERAS_REINTENTO;
        if (!respuesta.rechazado() && pendiente.intento() < esperas.length) {
            synchronized (pendientes) {
                pendientes.add(pendiente.otraVez(ahora, esperas));
            }
            return;
        }
        if (pendiente.pregunta()) {
            // The rider did not get the button: the warning still goes out at the limit.
            preguntasFallidas.incrementAndGet();
            LOGGER.warn("The crash question of guard {} did not reach the rider: {}",
                    pendiente.guardiaId(), respuesta.estado());
            return;
        }
        if (respuesta.rechazado()) {
            if (pendiente.contactoId() != null) {
                try {
                    destinatarios.rechazar(pendiente.contactoId());
                } catch (StorageException e) {
                    LOGGER.warn("Contact {} could not be marked as refused", pendiente.contactoId(), e);
                }
            }
        } else {
            avisosPerdidos.incrementAndGet();
            LOGGER.warn("A safety message was given up after {} retries", ESPERAS_REINTENTO.length);
        }
        resolver(pendiente);
    }

    /** One recipient served: noted in the guard, which is warned when none is left. */
    private synchronized void resolver(Pendiente pendiente) {
        if (pendiente.pregunta()) {
            return;
        }
        Set<String> faltan = enCurso.get(pendiente.guardiaId());
        if (faltan != null) {
            faltan.remove(pendiente.chatId());
        }
        try {
            Guardia guardia = leer(pendiente.guardiaId());
            if (guardia == null) {
                enCurso.remove(pendiente.guardiaId());
                return;
            }
            String resueltos = guardia.getResueltos() != null ? guardia.getResueltos() : ",";
            if (!resuelto(guardia, pendiente.chatId())) {
                guardia.setResueltos(resueltos + pendiente.chatId() + ",");
            }
            if (faltan == null || faltan.isEmpty()) {
                guardia.setEstado(Guardia.AVISADA);
                enCurso.remove(pendiente.guardiaId());
            }
            actualizar(guardia);
        } catch (StorageException e) {
            // Left AVISANDO: after a restart this recipient gets it again, never zero times.
            LOGGER.warn("Crash guard {} could not note a sent warning", pendiente.guardiaId(), e);
        }
    }

    private void reintentar(long ahora) {
        List<Pendiente> vencidos = new ArrayList<>();
        synchronized (pendientes) {
            for (Iterator<Pendiente> iterator = pendientes.iterator(); iterator.hasNext();) {
                Pendiente pendiente = iterator.next();
                if (pendiente.cuando() <= ahora) {
                    vencidos.add(pendiente);
                    iterator.remove();
                }
            }
        }
        vencidos.forEach(pendiente -> envios.execute(() -> enviar(pendiente, ahora)));
    }

    /** The positions around a guard: from before the crash (margin) until now. */
    private List<Position> posiciones(Guardia guardia, long ahora) throws StorageException {
        return posiciones(guardia.getDeviceId(),
                new Date(guardia.getGolpe().getTime() - MARGEN_FIJACION), new Date(ahora));
    }

    private static List<Position> despues(Guardia guardia, List<Position> todas) {
        return todas.stream()
                .filter(p -> p.getDeviceTime() != null && p.getDeviceTime().after(guardia.getGolpe()))
                .toList();
    }

    /** Positions of a device by fix time (indexed), device time decided by the caller. */
    private List<Position> posiciones(long deviceId, Date desde, Date hasta) throws StorageException {
        try (var stream = storage.getObjectsStream(Position.class, new Request(new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", desde, hasta)),
                new Order("fixTime", true, POSICIONES_MAXIMAS)))) {
            return stream.toList();
        }
    }

    private Guardia leer(long id) throws StorageException {
        return storage.getObject(Guardia.class, new Request(new Columns.All(), new Condition.Equals("id", id)));
    }

    private void actualizar(Guardia guardia) throws StorageException {
        storage.updateObject(guardia, new Request(
                new Columns.Include("estado", "enlace", "resueltos"), new Condition.Equals("id", guardia.getId())));
    }

    private String moto(long deviceId) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        return device != null ? device.getName() : "vehículo";
    }

}
