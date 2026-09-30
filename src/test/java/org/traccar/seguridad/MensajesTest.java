package org.traccar.seguridad;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words of the safety messages. They go to people who do not use Nordés.
 */
public class MensajesTest {

    // 2026-09-21 16:13:20 in Madrid (UTC+2)
    private static final Date HORA = new Date(1_790_000_000_000L);

    @Test
    public void theOwnerIsAskedAndToldWhatComesNext() {
        String texto = Mensajes.alDuenoTrasGolpe("Ducati");
        assertTrue(texto.contains("golpe en tu Ducati"), texto);
        assertTrue(texto.contains("2 minutos"), texto);
    }

    @Test
    public void theContactGetsWhoWhatWhenWhereAndTheLink() {
        String texto = Mensajes.aContacto("Juan", "Ducati", HORA, "Rúa Real 1, Ferrol", "https://n.es/?token=t");
        assertTrue(texto.contains("Juan"), texto);
        assertTrue(texto.contains("Ducati"), texto);
        assertTrue(texto.contains("16:13"), texto);
        assertTrue(texto.contains("Rúa Real 1, Ferrol"), texto);
        assertTrue(texto.contains("https://n.es/?token=t"), texto);
        assertTrue(texto.contains("Nordés no avisa al 112"), texto);
    }

    @Test
    public void withoutALinkThereIsNoEmptyLine() {
        String texto = Mensajes.aContacto("Juan", "Ducati", HORA, "Ferrol", null);
        assertFalse(texto.contains("null"), texto);
        assertFalse(texto.contains("directo"), texto);
    }

    @Test
    public void withoutAnAddressTheCoordinatesGo() {
        assertTrue(Mensajes.lugar(null, 43.47051, -8.21049).contains("43.47051, -8.21049"));
        assertTrue(Mensajes.lugar("  ", 43.47051, -8.21049).contains("43.47051"));
        assertTrue(Mensajes.lugar("Ferrol", 43.47051, -8.21049).equals("Ferrol"));
    }

    @Test
    public void aRideMateIsToldWhichRide() {
        String texto = Mensajes.aCompanero("Ruta del Eume", "Juan", "Ducati", HORA, "Ferrol", null);
        assertTrue(texto.startsWith("Compañero de la quedada «Ruta del Eume»"), texto);
        assertTrue(texto.contains("Nordés no avisa al 112"), texto);
    }

    @Test
    public void aDepartureSaysFromWhereAndGivesTheLink() {
        String texto = Mensajes.salida("Juan", "Casa", "Ducati", "https://n.es/?token=v");
        assertTrue(texto.startsWith("Juan ha salido de Casa con su Ducati."), texto);
        assertTrue(texto.contains("https://n.es/?token=v"), texto);
    }

    @Test
    public void anArrivalWithOrWithoutAPlace() {
        assertTrue(Mensajes.llegada("Juan", "Trabajo").equals("Juan ha llegado bien a Trabajo."));
        assertTrue(Mensajes.llegada("Juan", null).equals("Juan ha llegado bien."));
    }

    @Test
    public void theRiderKnowsWhatTheButtonIsFor() {
        assertTrue(Mensajes.alDuenoViajeAbierto().contains("pulsa el botón"));
    }

    @Test
    public void whenNoPositionHasAFixTheMessageSaysSoAndPointsToTheLiveMap() {
        assertEquals("un lugar que el GPS aún no ha fijado (mira la ubicación en directo)",
                Mensajes.lugarDesconocido(true));
        String texto = Mensajes.aContacto(
                "Juan", "Ducati", HORA, Mensajes.lugarDesconocido(true), "https://n.es/?token=t");
        assertTrue(texto.contains(", en un lugar que el GPS aún no ha fijado"), texto);
    }

    @Test
    public void withoutALinkTheMessageDoesNotPromiseALiveMap() {
        // Found in review on 2026-09-30: it sent people to a live location it did not include.
        assertEquals("un lugar que el GPS aún no ha fijado", Mensajes.lugarDesconocido(false));
        String texto = Mensajes.aContacto("Juan", "Ducati", HORA, Mensajes.lugarDesconocido(false), null);
        assertFalse(texto.contains("directo"), texto);
    }

    @Test
    public void theLastPlaceBeforeTheCrashSaysWhatItIs() {
        assertEquals("Avenida del Mar, Ferrol (su última posición conocida antes del golpe)",
                Mensajes.lugarAntesDelGolpe("Avenida del Mar, Ferrol", 43.1, -8.2));
        assertEquals("43.10000, -8.20000 (su última posición conocida antes del golpe)",
                Mensajes.lugarAntesDelGolpe(null, 43.1, -8.2));
    }

    @Test
    public void aDepartureWithoutALinkHasNoEmptyLine() {
        String texto = Mensajes.salida("Juan", "Casa", "Ducati", null);
        assertEquals("Juan ha salido de Casa con su Ducati.\nTe avisaremos cuando llegue.", texto);
    }

    @Test
    public void theNewContactIsToldWhoAndThatNordesDoesNotCall112() {
        String texto = Mensajes.bienvenida("Juan", "Ducati");
        assertTrue(texto.startsWith("Hola. Ahora eres contacto de emergencia de Juan (Ducati) en Nordés."), texto);
        assertTrue(texto.contains("Nordés no avisa al 112."), texto);
        assertTrue(texto.contains("escribe /baja"), texto);
    }

    @Test
    public void aStaleCodeAndLeavingAreAnsweredPlainly() {
        assertEquals("Este enlace ha caducado o ya se usó. Pide uno nuevo a quien te lo mandó.", Mensajes.codigoNoValido());
        assertEquals("Hecho. Ya no recibirás avisos de Nordés. Si quieres volver, pide un enlace nuevo.", Mensajes.baja());
    }

}
