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
package org.traccar.model;

/**
 * Política de contraseñas (mejora 36): sólo se aplica al poner una contraseña
 * nueva —alta, cambio o recuperación—, nunca al entrar, así que las cuentas que
 * ya existen con una corta siguen entrando hasta que la cambien.
 *
 * Longitud antes que reglas de símbolos: es lo que de verdad frena a quien
 * prueba, y no empuja a «Moto2026!».
 */
public final class PoliticaContrasenas {

    public static final int LONGITUD_MINIMA = 8;

    private PoliticaContrasenas() {
    }

    public static void comprobar(String contrasena) {
        if (contrasena.codePointCount(0, contrasena.length()) < LONGITUD_MINIMA) {
            throw new IllegalArgumentException(
                    "La contraseña necesita al menos " + LONGITUD_MINIMA + " caracteres");
        }
        if (contrasena.codePoints().distinct().count() < 3) {
            throw new IllegalArgumentException("La contraseña es demasiado simple");
        }
    }

}
