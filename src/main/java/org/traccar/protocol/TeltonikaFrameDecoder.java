/*
 * Copyright 2013 - 2022 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;
import org.traccar.BaseProtocol;

public class TeltonikaFrameDecoder extends BaseFrameDecoder {

    private static final int MESSAGE_MINIMUM_LENGTH = 12;

    // Los quince digitos de un IMEI, que es como empieza el saludo del equipo.
    private static final int IMEI_LENGTH = 15;

    public TeltonikaFrameDecoder() {
        super(BaseProtocol.MAX_FRAME_LENGTH_LARGE);
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (buf.isReadable() && buf.getByte(buf.readerIndex()) == (byte) 0xff) {
            return buf.readRetainedSlice(1);
        }

        if (buf.readableBytes() < MESSAGE_MINIMUM_LENGTH) {
            return null;
        }

        // RECHAZO TEMPRANO DE LO QUE NO PUEDE SER UNA TRAMA.
        //
        // Este puerto esta abierto a internet y no pide credencial. En el log de
        // un servidor de verdad hay 239 conexiones de 12 IPs, y la mayoria es
        // barrido: "GET / HTTP/1.1", "SSH-2.0-Go", lineas sueltas. Todo eso
        // entraba, reservaba sesion y dejaba su rastro en el log.
        //
        // Una trama de Teltonika empieza SIEMPRE por uno de dos sitios: la
        // longitud del IMEI (0x000F, quince digitos) o los cuatro ceros del
        // preambulo AVL. Cualquier otra cosa se cierra aqui.
        int first = buf.getUnsignedShort(buf.readerIndex());
        if (first != IMEI_LENGTH && first != 0) {
            throw new UnsupportedOperationException("not a teltonika frame");
        }

        int length = buf.getUnsignedShort(buf.readerIndex());
        if (length > 0) {
            if (buf.readableBytes() >= (length + 2)) {
                return buf.readRetainedSlice(length + 2);
            }
        } else {
            int dataLength = buf.getInt(buf.readerIndex() + 4);
            if (buf.readableBytes() >= (dataLength + 12)) {
                return buf.readRetainedSlice(dataLength + 12);
            }
        }

        return null;
    }

}
