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
package org.traccar.api.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueo por cuenta: tras {@link #MAX_FALLOS} contraseñas o códigos fallados en
 * {@link #VENTANA_MS}, la cuenta no entra durante {@link #BLOQUEO_MS}, ni siquiera
 * con la contraseña buena. La clave es el correo tal como se escribe, exista o
 * no, para que el bloqueo no delate qué cuentas hay.
 *
 * Contrapartida conocida: quien sepa un correo puede tener esa cuenta cerrada
 * quince minutos a base de fallar. Se acepta: es mucho menos grave que dejar
 * probar contraseñas sin freno.
 */
public class BloqueoCuentas {

    public static final int MAX_FALLOS = 5;
    public static final long VENTANA_MS = 15 * 60 * 1000L;
    public static final long BLOQUEO_MS = 15 * 60 * 1000L;
    private static final int LIMPIAR_POR_ENCIMA_DE = 10_000;

    private static final class Registro {
        private int fallos;
        private long primerFallo;
        private long bloqueadaHasta;
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();

    /** Segundos que faltan para poder volver a intentarlo; 0 si no está bloqueada. */
    public long segundosBloqueada(String cuenta, long ahora) {
        Registro registro = registros.get(cuenta);
        if (registro == null) {
            return 0;
        }
        synchronized (registro) {
            long resta = registro.bloqueadaHasta - ahora;
            return resta > 0 ? (resta + 999) / 1000 : 0;
        }
    }

    public void fallo(String cuenta, long ahora) {
        if (registros.size() > LIMPIAR_POR_ENCIMA_DE) {
            registros.entrySet().removeIf(e -> caducado(e.getValue(), ahora));
        }
        Registro registro = registros.computeIfAbsent(cuenta, k -> new Registro());
        synchronized (registro) {
            if (registro.bloqueadaHasta > ahora) {
                return;
            }
            if (registro.fallos == 0 || ahora - registro.primerFallo > VENTANA_MS) {
                registro.fallos = 0;
                registro.primerFallo = ahora;
            }
            registro.fallos += 1;
            if (registro.fallos >= MAX_FALLOS) {
                registro.bloqueadaHasta = ahora + BLOQUEO_MS;
                registro.fallos = 0;
            }
        }
    }

    public void acierto(String cuenta) {
        registros.remove(cuenta);
    }

    private static boolean caducado(Registro registro, long ahora) {
        synchronized (registro) {
            return registro.bloqueadaHasta <= ahora && ahora - registro.primerFallo > VENTANA_MS;
        }
    }

}
