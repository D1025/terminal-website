package com.fonline.newdawn.update;

import com.fonline.newdawn.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyUpdateGatewayTest {
    private static final Charset WIRE_CHARSET = Charset.forName("windows-1252");

    @Test
    void servesHello2ManifestAndFileWithLegacyWireFormat() throws Exception {
        byte[] payload = "legacy payload".getBytes(WIRE_CHARSET);
        UpdateService updates = mock(UpdateService.class);
        StorageService storage = mock(StorageService.class);
        when(updates.legacySnapshot("STABLE")).thenReturn(new UpdateService.LegacySnapshot(
                "0.2.2", "server.fonline-nd.com", 2238,
                List.of(new UpdateService.LegacyFile(
                        "data/patch001.zip", "PRESERVE", payload.length, -1234567, "updates/object"))));
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(1)).write(payload);
            return null;
        }).when(storage).writeTo(eq("updates/object"), any(OutputStream.class));

        LegacyUpdateGateway gateway = new LegacyUpdateGateway(
                updates, storage, "127.0.0.1", 0, "STABLE", "FOnline", "https://fonline-nd.com");
        gateway.start();
        try (Socket socket = new Socket("127.0.0.1", gateway.localPort());
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(3_000);
            writeLine(output, "Hello2");
            assertEquals("Greetings", readLine(input));
            assertEquals("FOnline", readLine(input));
            assertEquals("https://fonline-nd.com", readLine(input));
            assertEquals("server.fonline-nd.com", readLine(input));
            assertEquals("2238", readLine(input));
            assertEquals("", readLine(input));
            assertEquals("", readLine(input));
            assertEquals("", readLine(input));

            writeLine(output, "Give hashes list");
            assertEquals("Take hashes list extended", readLine(input));
            assertEquals(1, readLittleEndianInt(input));
            assertEquals("data\\patch001.zip", readLine(input));
            assertEquals("-1234567", readLine(input));
            assertEquals(Integer.toString(payload.length), readLine(input));
            assertEquals("<norewrite>", readLine(input));

            writeLine(output, "Get");
            writeLine(output, "data\\patch001.zip");
            assertEquals("Catch", readLine(input));
            assertEquals(payload.length, readLittleEndianInt(input));
            assertArrayEquals(payload, input.readNBytes(payload.length));
            writeLine(output, "Bye");
        } finally {
            gateway.stop();
        }
        verify(storage).writeTo(eq("updates/object"), any(OutputStream.class));
    }

    @Test
    void sendsUnsignedCrcToHello1Launchers() throws Exception {
        UpdateService updates = mock(UpdateService.class);
        StorageService storage = mock(StorageService.class);
        int signedCrc = -936_757_462;
        when(updates.legacySnapshot("STABLE")).thenReturn(new UpdateService.LegacySnapshot(
                "0.2.1", "server.fonline-nd.com", 2238,
                List.of(new UpdateService.LegacyFile(
                        "data/patch010.zip", "REPLACE", 233_121, signedCrc, "updates/patch010"))));

        LegacyUpdateGateway gateway = new LegacyUpdateGateway(
                updates, storage, "127.0.0.1", 0, "STABLE", "FOnline", "https://fonline-nd.com");
        gateway.start();
        try (Socket socket = new Socket("127.0.0.1", gateway.localPort());
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(3_000);
            writeLine(output, "Hello1");
            assertEquals("Greetings", readLine(input));

            writeLine(output, "Give hashes list");
            assertEquals("Take hashes list", readLine(input));
            assertEquals(1, readLittleEndianInt(input));
            assertEquals("data\\patch010.zip", readLine(input));
            assertEquals("3358209834", readLine(input));
            writeLine(output, "Bye");
        } finally {
            gateway.stop();
        }
    }

    private static void writeLine(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(WIRE_CHARSET));
        output.write('\r');
        output.write('\n');
        output.flush();
    }

    private static String readLine(InputStream input) throws IOException {
        byte[] bytes = new byte[4096];
        int length = 0;
        while (length < bytes.length) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (value == '\n') {
                if (length > 0 && bytes[length - 1] == '\r') length -= 1;
                return new String(bytes, 0, length, WIRE_CHARSET);
            }
            bytes[length++] = (byte) value;
        }
        throw new IOException("Test protocol line exceeded buffer.");
    }

    private static int readLittleEndianInt(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(4);
        if (bytes.length != 4) throw new EOFException();
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
