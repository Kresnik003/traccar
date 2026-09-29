package org.traccar.seguridad;

import com.google.inject.servlet.RequestScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.traccar.api.EnlacesDirectos;
import org.traccar.telegram.BuzonTelegram;
import org.traccar.telegram.ClienteTelegram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The services of the safety block are created at start-up, outside any HTTP
 * request, because the inbox is started with the server. A dependency that only
 * lives inside a request cannot be handed to them: on 2026-09-29 the bench did
 * not start because EnlacesDirectos asked for PermissionsService.
 */
public class AlcanceServiciosTest {

    private static final List<Class<?>> SERVICIOS = List.of(
            BuzonTelegram.class, ClienteTelegram.class, EnlacesDirectos.class, GuardianGolpes.class,
            ViajesFamilia.class, Destinatarios.class, SitiosPropios.class);

    @Test
    public void noServiceDependsOnARequest() {
        List<String> fallos = new ArrayList<>();
        for (Class<?> servicio : SERVICIOS) {
            for (Constructor<?> constructor : servicio.getConstructors()) {
                if (constructor.isAnnotationPresent(Inject.class)) {
                    for (Class<?> tipo : constructor.getParameterTypes()) {
                        if (tipo.isAnnotationPresent(RequestScoped.class)) {
                            fallos.add(servicio.getSimpleName() + " <- " + tipo.getSimpleName());
                        }
                    }
                }
            }
            for (Field field : servicio.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class) && field.getType().isAnnotationPresent(RequestScoped.class)) {
                    fallos.add(servicio.getSimpleName() + " <- " + field.getType().getSimpleName());
                }
            }
        }
        assertEquals(List.of(), fallos);
    }

}
