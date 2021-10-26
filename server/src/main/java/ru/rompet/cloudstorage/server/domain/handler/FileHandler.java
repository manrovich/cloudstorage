package ru.rompet.cloudstorage.server.domain.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import ru.rompet.cloudstorage.common.Response;
import ru.rompet.cloudstorage.common.Request;
import ru.rompet.cloudstorage.common.enums.Parameter;
import ru.rompet.cloudstorage.server.dao.AuthenticationService;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileHandler extends SimpleChannelInboundHandler<Request> {
    private final int BUFFER_SIZE = 1024 * 512;
    private final String DEFAULT_FILE_LOCATION = "serverFiles\\";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Request request) throws Exception {
        switch (request.getCommand()) {
            case LOAD -> load(ctx, request);
            case SAVE -> save(ctx, request);
            case DELETE -> delete(ctx, request);
            case DIR -> dir(ctx, request);
            case AUTH -> auth(ctx, request);
            case REGISTER -> register(ctx, request);
        }
    }

    private void load(ChannelHandlerContext ctx, Request request) throws Exception {
        Path path = Path.of(DEFAULT_FILE_LOCATION + request.getFromPath());
        if (Files.exists(path)) {
            if (Files.isRegularFile(path)) {
                ctx.writeAndFlush(readPartFile(request));
            } else if (Files.isDirectory(path)) {
                request.setFromPath(request.getFromPath() + "\\"); // can't do it on client side, because it can be file without extension
                request.setToPath(request.getToPath() + "\\");
                List<Path> filePaths = listFiles(
                        Path.of(request.getFromPath()),
                        Path.of(DEFAULT_FILE_LOCATION),
                        request.getParameters().contains(Parameter.R));
                for (Path filePath : filePaths) {
                    Request request1 = (Request) request.clone();
                    request1.setFromPath(request.getFromPath() + filePath.toString());
                    request1.setToPath(request.getToPath() + filePath.toString());
                    ctx.writeAndFlush(readPartFile(request1));
                }
            }
        } else {
            Response response = new Response(request);
            response.getErrorInfo().setSuccessful(false);
            response.getErrorInfo().setFileNotExists(true);
            ctx.writeAndFlush(response);
        }
    }

    private void save(ChannelHandlerContext ctx, Request request) throws Exception {
        if (!request.hasData()) { // first initial request
            ctx.writeAndFlush(new Response(request));
            createDirectoryIfNotExists(request);
        } else {
            writePartFile(request);
            if (!request.getPartFileInfo().isLastPart()) {
                Response response = new Response(request);
                response.getPartFileInfo().addPosition(request, BUFFER_SIZE);
                ctx.writeAndFlush(response);
            }
        }
    }

    private boolean delete(ChannelHandlerContext ctx, Request request) throws Exception {
        File file = new File(DEFAULT_FILE_LOCATION + request.getFromPath());
        Response response = new Response(request);
        if (file.delete()) {
            ctx.writeAndFlush(response);
            return true;
        } else {
            response.getErrorInfo().setSuccessful(false);
            ctx.writeAndFlush(response);
            return false;
        }
    }

    private void dir(ChannelHandlerContext ctx, Request request) throws Exception {
        Response response = new Response(request);
        try {
            response.getDirectoryStructure().scan(DEFAULT_FILE_LOCATION + request.getFromPath());
        } catch (NoSuchFileException e) {
            response.getErrorInfo().setSuccessful(false);
            response.getErrorInfo().setFileNotExists(true);
        }
        ctx.writeAndFlush(response);
    }

    private void auth(ChannelHandlerContext ctx, Request request) {
        Response response = new Response(request);
        response.setAuthenticated(
                AuthenticationService.auth(
                        request.getCredentials().getLogin(),
                        request.getCredentials().getPassword()
                )
        );
        ctx.writeAndFlush(response);
    }

    private void register(ChannelHandlerContext ctx, Request request) {
        Response response = new Response(request);
        response.getErrorInfo().setSuccessful(AuthenticationService.register(
                request.getCredentials().getLogin(),
                request.getCredentials().getPassword()
        ));
        ctx.writeAndFlush(response);
    }

    private Response readPartFile(Request request) throws Exception {
        Response response = new Response(request);
        response.getPartFileInfo().setPosition(request);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (RandomAccessFile accessFile =
                     new RandomAccessFile(DEFAULT_FILE_LOCATION + request.getFromPath(), "r")) {
            accessFile.seek(request.getPartFileInfo().getPosition());
            int read = accessFile.read(buffer);
            if (read == -1) {
                response.getPartFileInfo().setFile(new byte[0]);
                response.getPartFileInfo().setLastPart(true);
            } else if (read < buffer.length - 1) {
                byte[] tempBuffer = new byte[read];
                System.arraycopy(buffer, 0, tempBuffer, 0, read);
                response.getPartFileInfo().setFile(tempBuffer);
                response.getPartFileInfo().setLastPart(true);
            } else {
                response.getPartFileInfo().setFile(buffer);
                response.getPartFileInfo().setLastPart(false);
            }
            return response;
        }
    }

    private void writePartFile(Request request) throws Exception {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(
                DEFAULT_FILE_LOCATION + request.getToPath(), "rw")) {
            randomAccessFile.seek(request.getPartFileInfo().getPosition());
            randomAccessFile.write(request.getPartFileInfo().getFile());
        }
    }

    private void createDirectoryIfNotExists(Request request) throws Exception {
        Path path = Path.of(DEFAULT_FILE_LOCATION + request.getToPath()).getParent();
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
    }

    private List<Path> listFiles(Path path, Path root, boolean recursive) throws IOException {
        List<Path> result;
        Path fullPath = root.resolve(path);
        try (Stream<Path> walk = Files.walk(fullPath, recursive ? Integer.MAX_VALUE : 1)) {
            result = walk
                    .filter(Files::isRegularFile)
                    .map(recursive ? fullPath::relativize : Path::getFileName)
                    .collect(Collectors.toList());
        }
        return result;
    }
}
