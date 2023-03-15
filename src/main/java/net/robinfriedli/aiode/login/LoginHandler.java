package net.robinfriedli.aiode.login;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.LoggerFactory;
import org.apache.hc.core5.http.ParseException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.robinfriedli.aiode.exceptions.InvalidRequestException;
import net.robinfriedli.aiode.servers.ServerUtil;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;

/**
 * Handler for the login flow that completes pending logins
 */
public class LoginHandler implements HttpHandler {

    private final ShardManager shardManager;
    private final SpotifyApi.Builder spotifyApiBuilder;
    private final LoginManager loginManager;

    public LoginHandler(ShardManager shardManager, SpotifyApi.Builder spotifyApiBuilder, LoginManager loginManager) {
        this.shardManager = shardManager;
        this.spotifyApiBuilder = spotifyApiBuilder;
        this.loginManager = loginManager;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        String html = Files.readString(Path.of("html/login.html"));
        try {
            Map<String, String> parameterMap = ServerUtil.getParameters(httpExchange);

            String accessCode = parameterMap.get("code");
            String userId = parameterMap.get("state");
            String error = parameterMap.get("error");

            String response;
            if (accessCode != null) {
                User user;
                try {
                    user = shardManager.retrieveUserById(userId).complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                        throw new IllegalArgumentException(String.format("Could not find user with id '%s'. " +
                            "Please make sure the login link is valid and the bot is still a member of your guild.", userId));
                    }

                    throw e;
                }
                if (user == null) {
                    throw new InvalidRequestException(String.format("No user found for id '%s'", userId));
                }

                CompletableFuture<Login> pendingLogin = loginManager.getPendingLogin(user);
                createLogin(accessCode, user, pendingLogin);

                response = String.format(html, "<span style=\"color: hsl(0, 0%, 94%);\">Welcome, </span>" + user.getName());
            } else if (error != null) {
                response = String.format(html, "<span style=\"color: red\">Error:</span> " + error);
            } else {
                throw new InvalidRequestException("Missing parameter code or error");
            }

            httpExchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = httpExchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        } catch (InvalidRequestException e) {
            ServerUtil.handleError(httpExchange, e);
        } catch (Exception e) {
            ServerUtil.handleError(httpExchange, e);
            LoggerFactory.getLogger(getClass()).error("Error in HttpHandler", e);
        }
    }

    private void createLogin(String accessCode, User user, CompletableFuture<Login> pendingLogin) throws SpotifyWebApiException, IOException, ParseException {
        SpotifyApi spotifyApi = spotifyApiBuilder.build();
        AuthorizationCodeCredentials credentials = spotifyApi.authorizationCode(accessCode).build().execute();
        String accessToken = credentials.getAccessToken();
        String refreshToken = credentials.getRefreshToken();
        Integer expiresIn = credentials.getExpiresIn();

        Login login = new Login(user, accessToken, refreshToken, expiresIn, spotifyApi);
        loginManager.addLogin(login);
        loginManager.removePendingLogin(user);
        pendingLogin.complete(login);
    }
}
