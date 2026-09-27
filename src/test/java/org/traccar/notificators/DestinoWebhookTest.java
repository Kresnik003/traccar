package org.traccar.notificators;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DestinoWebhookTest {

    private static void rechaza(String url, String motivo) {
        var e = assertThrows(IllegalArgumentException.class, () -> DestinoWebhook.comprobar(url), url);
        assertTrue(e.getMessage().contains(motivo), url + ": " + e.getMessage());
    }

    @Test
    public void testNadaHaciaDentro() {
        for (String url : new String[] {
                "https://127.0.0.1/x", "https://localhost:8082/api/server", "https://10.0.0.81/",
                "https://192.168.185.246/", "https://172.16.5.4/", "https://169.254.169.254/latest/meta-data",
                "https://100.100.100.100/", "https://0.0.0.0/", "https://[::1]/", "https://[fd7a:115c:a1e0::1]/",
                "https://[::ffff:127.0.0.1]/", "https://224.0.0.1/", "https://255.255.255.255/"}) {
            rechaza(url, "interna");
        }
    }

    @Test
    public void testSoloHttpsYBienEscrita() {
        rechaza("http://8.8.8.8/hook", "https");
        rechaza("ftp://8.8.8.8/hook", "mal escrita");
        rechaza("https://usuario:clave@8.8.8.8/", "mal escrita");
        rechaza("no es una url", "mal escrita");
        rechaza("", "Sin URL");
        rechaza(null, "Sin URL");
    }

    @Test
    public void testUnaPublicaVale() {
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("https://8.8.8.8/hook"));
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("https://1.1.1.1:8443/a?b=c"));
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("https://100.63.0.1/"));
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("https://[2606:4700:4700::1111]/"));
    }

    @Test
    public void testLaListaDelAdministradorAbreSoloEsoExacto() {
        Set<String> permitidos = DestinoWebhook.permitidos(" 127.0.0.1:8990 , Casa.Local:8123");
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("http://127.0.0.1:8990/aviso", permitidos));
        assertDoesNotThrow(() -> DestinoWebhook.comprobar("http://casa.local:8123/api/webhook/x", permitidos));
        assertThrows(IllegalArgumentException.class,
                () -> DestinoWebhook.comprobar("http://127.0.0.1:8082/api/server", permitidos), "otro puerto");
        assertThrows(IllegalArgumentException.class,
                () -> DestinoWebhook.comprobar("https://127.0.0.1/", permitidos), "el 443 no está en la lista");
        assertTrue(DestinoWebhook.permitidos(null).isEmpty());
    }

}
