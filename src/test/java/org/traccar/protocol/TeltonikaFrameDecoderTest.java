package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TeltonikaFrameDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var channel = channel(inject(new TeltonikaFrameDecoder()));

        verifyDecode(channel, binary("FF000F313233343536373839303132333435"),
                binary("ff"),
                binary("000F313233343536373839303132333435"));

        verifyDecode(channel, binary("000F313233343536373839303132333435"),
                binary("000F313233343536373839303132333435"));

        // LA BASURA QUE LLEGA DE VERDAD A ESE PUERTO.
        //
        // Son cadenas sacadas del log de un servidor en produccion: 239
        // conexiones de 12 IPs, casi todas barrido de internet. Antes entraban,
        // reservaban sesion y dejaban su rastro; ahora se cierran aqui.
        //
        // Se prueban EN HEXADECIMAL de lo que llego, no inventadas:
        //   "GET / HTTP..."  -> 474554
        //   "SSH-2.0-Go" + fin de linea  -> 5353482d
        var decoder = inject(new TeltonikaFrameDecoder());
        for (String basura : new String[] {
                "474554202f20485454502f312e310d0a486f73743a20",
                "5353482d322e302d476f0d0a0000000000000000",
                "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"}) {
            var buf = binary(basura);
            assertThrows(UnsupportedOperationException.class, () ->
                    decoder.decode(null, null, buf));
            // Y NO SE RECHAZA DOS VECES. Al cerrar la conexion Netty vuelve a
            // descodificar lo que quede en el buffer; si quedaba la basura, el
            // log sacaba el mismo aviso otra vez.
            assertNull(decoder.decode(null, null, buf));
        }

        // Y LO QUE SI ES UNA TRAMA SIGUE PASANDO. Esta es la parte que de
        // verdad importa: un filtro que tambien tire las buenas deja la moto
        // sin reportar y nadie lo ve hasta que hace falta el recorrido.
        verifyDecode(channel, binary("000F333538343830303830353833333135"),
                binary("000F333538343830303830353833333135"));

        // Y UNA TRAMA AVL DE VERDAD, que es la que lleva las posiciones.
        //
        // Este es el caso que de verdad hay que proteger: un filtro demasiado
        // estricto que tire las tramas buenas deja la moto sin reportar, y eso
        // no da ningun error -simplemente dejan de llegar posiciones- hasta el
        // dia que hace falta el recorrido. Empieza por el preambulo AVL, cuatro
        // ceros, no por la longitud del IMEI.
        verifyDecode(channel, binary("000000000000003f8e0100000199155c620001000000000000000000000000000000010100010000000000000000000101010012006400000000000000c800000bb80fa00000010000566e"),
                binary("000000000000003f8e0100000199155c620001000000000000000000000000000000010100010000000000000000000101010012006400000000000000c800000bb80fa00000010000566e"));

    }

}
