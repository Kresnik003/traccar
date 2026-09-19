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
package org.traccar.notification;

/**
 * How long to wait before trying a failed notification again.
 *
 * The wait doubles with every attempt and is capped, so a channel that is down
 * for an hour is not asked once a second for an hour. It is capped rather than
 * left to grow because the alternative - unbounded doubling - pushes the tenth
 * attempt days into the future, and a road alert that arrives days later is
 * worse than one that never arrives: it is confusing.
 *
 * The result is deliberately a pure function of the attempt number: the retry
 * task is hard to test end to end, this is not, and this is where the arithmetic
 * that can be wrong lives.
 */
public final class EsperaReintento {

    private EsperaReintento() {
    }

    /**
     * Wait before attempt number {@code intento}, in milliseconds.
     *
     * @param intento how many attempts already failed, starting at 1
     * @param base    the first wait
     * @param tope    the longest wait allowed
     */
    public static long espera(int intento, long base, long tope) {
        if (intento < 1) {
            // Attempt zero does not exist. Answering with the base wait rather
            // than throwing keeps a bad row from stopping the whole queue.
            return base;
        }
        if (base <= 0) {
            return 0;
        }
        long espera = base;
        for (int i = 1; i < intento; i++) {
            // Doubling by multiplication overflows long around the 63rd attempt
            // and comes back NEGATIVE, which would make the row due forever and
            // spin the retry task. Stopping at the cap avoids ever getting there.
            if (espera >= tope) {
                return tope;
            }
            espera *= 2;
        }
        return Math.min(espera, tope);
    }

    /**
     * Whether to keep trying.
     *
     * @param intentos attempts already made
     * @param maximo   how many are allowed; zero or less means never give up
     */
    public static boolean quedanIntentos(int intentos, int maximo) {
        return maximo <= 0 || intentos < maximo;
    }

    /**
     * Whether a queued notification gets another retry.
     *
     * The queue counts ATTEMPTS, and the first one is the original send that
     * failed and put the row there. notificator.retryAttempts counts RETRIES.
     * Comparing one with the other directly, as the first version did, gave N
     * retries as N - 1.
     *
     * @param intentosHechos attempts made so far, the original send included
     * @param reintentos     notificator.retryAttempts
     */
    public static boolean quedanReintentos(int intentosHechos, int reintentos) {
        return quedanIntentos(intentosHechos - 1, reintentos);
    }

    /**
     * The reason a delivery failed, short enough to store.
     *
     * A Java stack trace is kilobytes per row and can carry the channel URL,
     * token included, inside the message. Only the first line is kept, cut.
     */
    public static String motivo(Throwable e, int limite) {
        if (e == null) {
            return null;
        }
        // Down to the root cause, ALWAYS - not only when the message is null.
        // The first version stopped at the first exception that had a message,
        // and that is almost never the useful one: CompletableFuture wraps every
        // failure in a CompletionException whose message is the cause toString,
        // so every row would have read \"CompletionException: org.traccar...\"
        // instead of \"SocketTimeoutException: connect timed out\". The test
        // caught it.
        Throwable causa = e;
        int saltos = 0;
        // A cycle in the cause chain is rare but real, and an unbounded walk
        // here would hang the retry task.
        while (causa.getCause() != null && causa.getCause() != causa && saltos < 16) {
            causa = causa.getCause();
            saltos++;
        }
        String texto = causa.getClass().getSimpleName();
        if (causa.getMessage() != null) {
            texto = texto + ": " + causa.getMessage();
        }
        int salto = texto.indexOf('\n');
        if (salto >= 0) {
            texto = texto.substring(0, salto);
        }
        return texto.length() > limite ? texto.substring(0, limite) : texto;
    }

}
