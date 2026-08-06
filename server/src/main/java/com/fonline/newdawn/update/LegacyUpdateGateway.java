package com.fonline.newdawn.update;

import com.fonline.newdawn.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnProperty(prefix = "app.legacy-updater", name = "enabled", havingValue = "true")
public class LegacyUpdateGateway {
    private static final Logger log = LoggerFactory.getLogger(LegacyUpdateGateway.class);
    private static final Charset WIRE_CHARSET = Charset.forName("windows-1252");
    private static final int MAX_LINE_BYTES = 4096;

    private final UpdateService updates;
    private final StorageService storage;
    private final String bindAddress;
    private final int port;
    private final String channel;
    private final String gameFileName;
    private final String newsUrl;
    private final ExecutorService clients = Executors.newFixedThreadPool(16);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LegacyUpdateGateway(UpdateService updates, StorageService storage,
                               @Value("${app.legacy-updater.bind-address:0.0.0.0}") String bindAddress,
                               @Value("${app.legacy-updater.port:4040}") int port,
                               @Value("${app.legacy-updater.channel:STABLE}") String channel,
                               @Value("${app.legacy-updater.game-file-name:FOnline}") String gameFileName,
                               @Value("${app.legacy-updater.news-url:https://fonline-nd.com}") String newsUrl) {
        this.updates = updates;
        this.storage = storage;
        this.bindAddress = bindAddress;
        this.port = port;
        this.channel = channel;
        this.gameFileName = gameFileName;
        this.newsUrl = newsUrl;
    }

    @PostConstruct
    void start() throws IOException {
        serverSocket = new ServerSocket(port, 64, InetAddress.getByName(bindAddress));
        running = true;
        acceptThread = Thread.ofPlatform().daemon().name("legacy-updater-accept").start(this::acceptLoop);
        log.warn("Legacy plaintext updater gateway is enabled on {}:{}. Use it only during launcher migration.",
                bindAddress, port);
    }

    @PreDestroy
    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException exception) {
            log.debug("Legacy updater socket was already closed.", exception);
        }
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    int localPort() {
        if (serverSocket == null) throw new IllegalStateException("Legacy updater gateway has not started.");
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(30_000);
                socket.setTcpNoDelay(true);
                clients.submit(() -> handle(socket));
            } catch (IOException exception) {
                if (running) log.error("Legacy updater accept failed.", exception);
            }
        }
    }

    private void handle(Socket socket) {
        String peer = socket.getRemoteSocketAddress().toString();
        try (socket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            String hello = readLine(input);
            if (!"Hello2".equals(hello) && !"Hello1".equals(hello) && !"Hello".equals(hello)) return;

            UpdateService.LegacySnapshot snapshot = updates.legacySnapshot(channel);
            Map<String, UpdateService.LegacyFile> filesByPath = index(snapshot);
            writeLine(output, "Greetings");
            if ("Hello2".equals(hello)) {
                writeLine(output, gameFileName);
                writeLine(output, newsUrl);
                writeLine(output, valueOrEmpty(snapshot.gameServerHost()));
                writeLine(output, snapshot.gameServerPort() == null ? "" : snapshot.gameServerPort().toString());
                writeLine(output, "");
                writeLine(output, "");
                writeLine(output, "");
            }
            output.flush();

            while (running && !socket.isClosed()) {
                String command;
                try {
                    command = readLine(input);
                } catch (EOFException exception) {
                    break;
                }
                if ("Give hashes list".equals(command)) {
                    sendManifest(output, snapshot, "Hello2".equals(hello));
                } else if ("Get".equals(command) || "Get unpacked".equals(command)) {
                    String requestedPath = pathKey(readLine(input));
                    UpdateService.LegacyFile file = filesByPath.get(requestedPath);
                    if (file == null) break;
                    writeLine(output, "Catch");
                    writeLittleEndianInt(output, file.sizeBytes());
                    storage.writeTo(file.objectKey(), output);
                    output.flush();
                } else if ("Ping".equals(command)) {
                    writeLine(output, "PingOk");
                    output.flush();
                } else if ("Bye".equals(command)) {
                    break;
                } else {
                    break;
                }
            }
        } catch (Exception exception) {
            log.warn("Legacy updater session from {} ended with an error: {}", peer, exception.getMessage());
        }
    }

    private void sendManifest(OutputStream output, UpdateService.LegacySnapshot snapshot, boolean extended)
            throws IOException {
        writeLine(output, extended ? "Take hashes list extended" : "Take hashes list");
        writeLittleEndianInt(output, snapshot.files().size());
        for (UpdateService.LegacyFile file : snapshot.files()) {
            writeLine(output, legacyPath(file.path()));
            writeLine(output, extended
                    ? Integer.toString(file.legacyCrc32())
                    : Integer.toUnsignedString(file.legacyCrc32()));
            if (extended) {
                writeLine(output, Integer.toString(file.sizeBytes()));
                writeLine(output, "PRESERVE".equals(file.overwritePolicy()) ? "<norewrite>" : "");
            }
        }
        output.flush();
    }

    private Map<String, UpdateService.LegacyFile> index(UpdateService.LegacySnapshot snapshot) {
        Map<String, UpdateService.LegacyFile> result = new HashMap<>();
        for (UpdateService.LegacyFile file : snapshot.files()) result.put(pathKey(file.path()), file);
        return result;
    }

    private String readLine(InputStream input) throws IOException {
        byte[] bytes = new byte[MAX_LINE_BYTES];
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
        throw new IOException("Legacy protocol line exceeded " + MAX_LINE_BYTES + " bytes.");
    }

    private void writeLine(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(WIRE_CHARSET));
        output.write('\r');
        output.write('\n');
    }

    private void writeLittleEndianInt(OutputStream output, int value) throws IOException {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private String pathKey(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String legacyPath(String value) {
        return value.replace('/', '\\');
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
