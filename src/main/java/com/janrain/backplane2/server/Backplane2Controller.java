/*
 * Copyright 2011 Janrain, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.janrain.backplane2.server;

import com.janrain.backplane2.server.config.*;
import com.janrain.backplane2.server.dao.BackplaneMessageDAO;
import com.janrain.backplane2.server.dao.DaoFactory;
import com.janrain.commons.supersimpledb.SimpleDBException;
import com.janrain.crypto.ChannelUtil;
import com.janrain.crypto.HmacHashUtils;
import com.janrain.metrics.MetricsAccumulator;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.HistogramMetric;
import com.yammer.metrics.core.MeterMetric;
import com.yammer.metrics.core.TimerMetric;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.inject.Inject;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Backplane API implementation.
 *
 * @author Johnny Bufu
 */
@Controller
@RequestMapping(value="/v2/*")
@SuppressWarnings({"UnusedDeclaration"})
public class Backplane2Controller {

    // - PUBLIC

    public static final String DIRECT_RESPONSE = "direct_response"; // both view name and jsp variable

    /**
     * Handle dynamic discovery of this server's registration endpoint
     * @return
     */
    @RequestMapping(value = "/.well-known/host-meta", method = { RequestMethod.GET})
    public ModelAndView xrds(HttpServletRequest request, HttpServletResponse response) {

        ModelAndView view = new ModelAndView("xrd");
        view.addObject("host", "http://" + request.getServerName());
        view.addObject("secureHost", "https://" + request.getServerName());
        return view;
    }

    // todo: cleanup authsession, authorizationrequests... tables

    @RequestMapping(value = "/authorize", method = { RequestMethod.GET, RequestMethod.POST })
    public ModelAndView authorize(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            @CookieValue( value = AUTH_SESSION_COOKIE, required = false) String authSessionCookie,
                            @CookieValue( value = AUTHORIZATION_REQUEST_COOKIE, required = false) String authorizationRequestCookie,
                            @RequestHeader(value = "Authorization", required = false) String basicAuth) throws OAuth2AuthorizationException {

        AuthorizationRequest authzRequest = null;
        String httpMethod = request.getMethod();
        String authZdecisionKey = request.getParameter(AUTHZ_DECISION_KEY);

        // not return from /authenticate && not authz decision post
        if ( request.getParameterMap().size() > 0  &&  StringUtils.isEmpty(authZdecisionKey) ) { 
            // incoming authz request
            authzRequest = parseAuthZrequest(request, basicAuth);
        }

        String authenticatedBusOwner = getAuthenticatedBusOwner(request, authSessionCookie);
        if (null == authenticatedBusOwner) {
            if (null != authzRequest) {
                try {
                    logger.info("Persisting authorization request for client: " + authzRequest.get(AuthorizationRequest.Field.CLIENT_ID) +
                                "[" + authzRequest.get(AuthorizationRequest.Field.COOKIE)+"]");
                    daoFactory.getAuthorizationRequestDAO().persistAuthorizationRequest(authzRequest);
                    response.addCookie(new Cookie(AUTHORIZATION_REQUEST_COOKIE, authzRequest.get(AuthorizationRequest.Field.COOKIE)));
                } catch (SimpleDBException e) {
                    throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_SERVER_ERROR, e.getMessage(), request, e);
                }
            }
            logger.info("Bus owner not authenticated, redirecting to /authenticate");
            return new ModelAndView("redirect:/v2/authenticate");
        }

        if (StringUtils.isEmpty(authZdecisionKey)) {
            // authorization request
            if (null == authzRequest) {
                // return from /authenticate
                try {
                    logger.debug("bp2.authorization.request cookie = " + authorizationRequestCookie);
                    authzRequest = daoFactory.getAuthorizationRequestDAO().retrieveAuthorizationRequest(authorizationRequestCookie);
                    logger.info("Retrieved authorization request for client:" + authzRequest.get(AuthorizationRequest.Field.CLIENT_ID) +
                                "[" + authzRequest.get(AuthorizationRequest.Field.COOKIE)+"]");
                } catch (SimpleDBException e) {
                    throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_SERVER_ERROR, e.getMessage(), request, e);
                }
            }
            return processAuthZrequest(authzRequest, authSessionCookie, authenticatedBusOwner);
        } else {
            // authZ decision from bus owner, accept only on post
            if (! "POST".equals(httpMethod)) {
                throw new IllegalArgumentException("Invalid HTTP method for authorization decision post: " + httpMethod);
            }
            return processAuthZdecision(authZdecisionKey, authSessionCookie, authenticatedBusOwner, authorizationRequestCookie, request);
        }
    }

    /**
     * Authenticates a bus owner and stores the authenticated session (cookie) to simpleDB.
     *
     * GET: displays authentication form
     * POST: processes authentication and returns to /authorize
     */
    @RequestMapping(value = "/authenticate", method = { RequestMethod.GET, RequestMethod.POST })
    public ModelAndView authenticate(
                          HttpServletRequest request,
                          HttpServletResponse response,
                          @RequestParam(required = false) String busOwner,
                          @RequestParam(required = false) String password) throws AuthException, SimpleDBException {
        String httpMethod = request.getMethod();
        if ("GET".equals(httpMethod)) {
            logger.debug("returning view for GET");
            return new ModelAndView(BUS_OWNER_AUTH_FORM_JSP);
        } else if ("POST".equals(httpMethod)) {
            checkBusOwnerAuth(busOwner, password);
            persistAuthenticatedSession(response, busOwner);
            return new ModelAndView("redirect:/v2/authorize");
        } else {
            throw new IllegalArgumentException("Unsupported method for /authenticate: " + httpMethod);
        }
    }

    /**
     * The OAuth "Token Endpoint" is used to obtain an access token to be used
     * for retrieving messages from the Get Messages endpoint.
     *
     * @param request
     * @param response
     * @param scope     optional
     * @param callback  required
     * @return
     * @throws AuthException
     * @throws SimpleDBException
     * @throws BackplaneServerException
     */

    @RequestMapping(value = "/token", method = { RequestMethod.GET})
    @ResponseBody
    public HashMap<String,Object> getToken(HttpServletRequest request, HttpServletResponse response,
                                           @RequestParam(value = "scope", required = false) String scope,
                                           @RequestParam(required = true) String callback)
            throws AuthException, SimpleDBException, BackplaneServerException {

        TokenRequest tokenRequest = new TokenRequest("anonymous", "client_credentials", scope, callback);

        HashMap errors = tokenRequest.validate();

        if (!errors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return errors;
        }

        HashMap<String, Object> hash;

        try {
            hash= new OAuth2Response(tokenRequest, daoFactory).generateResponse();
        } catch (final BackplaneServerException bpe) {
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, bpe.getMessage());
            }};

        }

        if (StringUtils.isBlank(callback)) {
            throw new IllegalArgumentException("Callback cannot be blank");
        } else {
            response.setContentType("application/x-javascript");
            try {
                String responseBody = callback + "(" + new String(new ObjectMapper().writeValueAsString(hash) + ")");
                response.getWriter().print(responseBody);
                return null;
            } catch (IOException e) {
                String errMsg = "Error converting frames to JSON: " + e.getMessage();
                logger.error(errMsg, bpConfig.getDebugException(e));
                throw new BackplaneServerException(errMsg, e);
            }
        }
    }

    /**
     * The OAuth "Token Endpoint" is used to obtain an access token to be used
     * for retrieving messages from the Get Messages endpoint.
     *
     * @param request
     * @param response
     * @param client_id
     * @param grant_type
     * @param redirect_uri
     * @param code
     * @param client_secret
     * @param scope
     * @return
     * @throws AuthException
     * @throws SimpleDBException
     * @throws BackplaneServerException
     */

    @RequestMapping(value = "/token", method = { RequestMethod.POST})
    @ResponseBody
    public HashMap<String,Object> token(HttpServletRequest request, HttpServletResponse response,
                                        @RequestParam(value = "client_id", required = false) String client_id,
                                        @RequestParam(value = "grant_type", required = false) String grant_type,
                                        @RequestParam(value = "redirect_uri", required = false) String redirect_uri,
                                        @RequestParam(value = "code", required = false) String code,
                                        @RequestParam(value = "client_secret", required = false) String client_secret,
                                        @RequestParam(value = "scope", required = false) String scope)
            throws AuthException, SimpleDBException, BackplaneServerException {

        TokenRequest tokenRequest = new TokenRequest(daoFactory, client_id, grant_type, redirect_uri,
                                                        code, client_secret, scope, null);

        HashMap errors = tokenRequest.validate();
        if (!errors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return errors;
        }

        try {
            return new OAuth2Response(tokenRequest, daoFactory).generateResponse();
        } catch (final BackplaneServerException bpe) {
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, bpe.getMessage());
            }};

        }

    }

    /**
     * Retrieve messages from the server.
     * @param request
     * @param response
     * @return
     */

    @RequestMapping(value = "/messages", method = { RequestMethod.GET})
    public @ResponseBody HashMap<String,Object> messages(HttpServletRequest request, HttpServletResponse response,
                                @RequestParam(value = "access_token", required = false) String access_token,
                                @RequestParam(value = "block", defaultValue = "0", required = false) Integer block,
                                @RequestParam(required = false) String callback,
                                @RequestParam(value = "since", required = false) String since)
            throws SimpleDBException, BackplaneServerException {

        //TODO: add support for block?

        MessageRequest messageRequest = new MessageRequest(daoFactory, access_token, callback);

        HashMap<String, Object> errors = messageRequest.validate();
        if (!errors.isEmpty()) {
            return errors;
        }

        List<BackplaneMessage> messages =
                daoFactory.getBackplaneMessageDAO().retrieveAllMesssagesPerScope(messageRequest.getToken().getScope(), since);

        String nextUrl = "https://" + request.getServerName() + "/v2/messages";

        //TODO: the spec (sec 12, last paragraph) suggests that the "since" id must specify a message that exists and
        // if it does NOT exist, then we remove the "since" value from the query to allow the filter to be applied
        // against the entire message "current buffer".  Fix.

        if (messages.isEmpty()) {
            if (!StringUtils.isBlank(since)) {
                nextUrl += "?since=" + since;
            }
        } else {
                nextUrl += "?since=" + messages.get(messages.size()-1).getIdValue();
        }

        //String nextUrl = "https://" + request.getServerName() + "/v2/messages?since=" + messages.get(messages.size()-1).getIdValue();
        List<Map<String,Object>> frames = new ArrayList<Map<String, Object>>();

        for (BackplaneMessage message : messages) {
            frames.add(message.asFrame(request.getServerName(), messageRequest.getToken().isPrivileged()));

            // verify the proper permissions
            if (messageRequest.getToken().isPrivileged()) {
                if (!messageRequest.getToken().isAllowedBus(message.get(BackplaneMessage.Field.BUS))) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return new HashMap<String,Object>() {{
                        put(ERR_MSG_FIELD, "Forbidden");
                    }};
                }
            } else {
                if (!message.get(BackplaneMessage.Field.CHANNEL).equals(messageRequest.getToken().getChannelName())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return new HashMap<String,Object>() {{
                        put(ERR_MSG_FIELD, "Forbidden");
                    }};
                }
            }
        }

        HashMap<String, Object> hash = new HashMap<String, Object>();
        hash.put("nextURL", nextUrl);
        hash.put("messages", frames);

        if (StringUtils.isBlank(callback)) {
            response.setContentType("application/json");
            return hash;
        } else {
            response.setContentType("application/x-javascript");
            try {
                String responseBody = callback + "(" + new String(new ObjectMapper().writeValueAsString(hash) + ")");

                response.getWriter().print(responseBody);

                return null;
            } catch (IOException e) {
                String errMsg = "Error converting frames to JSON: " + e.getMessage();
                logger.error(errMsg, bpConfig.getDebugException(e));
                throw new BackplaneServerException(errMsg, e);
            }
        }
    }



    /**
     * Retrieve a single message from the server.
     * @param request
     * @param response
     * @return
     */

    @RequestMapping(value = "/message/{msg_id}", method = { RequestMethod.GET})
    public @ResponseBody HashMap<String,Object> message(HttpServletRequest request, HttpServletResponse response,
                                                        @PathVariable String msg_id,
                                                        @RequestParam(value = "access_token", required = false) String access_token,
                                                        @RequestParam(required = false) String callback)
            throws BackplaneServerException {

        MessageRequest messageRequest = new MessageRequest(daoFactory, access_token, callback);

        HashMap error = messageRequest.validate();
        if (!error.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return error;
        }

        BackplaneMessage message = null;

        try {
            message = daoFactory.getBackplaneMessageDAO().retrieveBackplaneMessage(msg_id);
        } catch (SimpleDBException e) {
            logger.info("Could not find message " + msg_id,e);
        }

        if (message == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, "Message not found");
            }};
        } else {
            // verify the proper permissions
            if (messageRequest.getToken().isPrivileged()) {
                if (!messageRequest.getToken().isAllowedBus(message.get(BackplaneMessage.Field.BUS))) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return new HashMap<String,Object>() {{
                        put(ERR_MSG_FIELD, "Forbidden");
                    }};
                }
            } else {
                if (!message.get(BackplaneMessage.Field.CHANNEL).equals(messageRequest.getToken().getChannelName())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return new HashMap<String,Object>() {{
                        put(ERR_MSG_FIELD, "Forbidden");
                    }};
                }
            }
        }

        error = messageRequest.validate();
        if (!error.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return error;
        }

        if (StringUtils.isBlank(callback)) {
            response.setContentType("application/json");
            return message.asFrame(request.getServerName(), messageRequest.getToken().isPrivileged());
        } else {
            response.setContentType("application/x-javascript");
            try {
                String responseBody = callback + "(" +
                        new String(new ObjectMapper().writeValueAsString(message.asFrame(request.getServerName(), messageRequest.getToken().isPrivileged())) + ")");

                response.getWriter().print(responseBody);

                return null;
            } catch (IOException e) {
                String errMsg = "Error converting frames to JSON: " + e.getMessage();
                logger.error(errMsg, bpConfig.getDebugException(e));
                throw new BackplaneServerException(errMsg, e);
            }
        }

    }

    /**
     * Publish messages to Backplane.
     * @param request
     * @param response
     * @return
     */

    @RequestMapping(value = "/messages", method = { RequestMethod.POST})
    public @ResponseBody HashMap<String,Object>  postMessages(HttpServletRequest request, HttpServletResponse response,
                                                              @RequestBody Map<String,List<Map<String,Object>>> messages,
                                                              @RequestParam(value = "access_token", required = false) String access_token,
                                                              @RequestParam(required = false) String callback,
                                                              @RequestParam(required = false) String since) throws BackplaneServerException, SimpleDBException {

        Token token = null;

        if (StringUtils.isEmpty(access_token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, "Forbidden");
            }};
        }

        try {
            token = daoFactory.getTokenDao().retrieveToken(access_token);
        } catch (SimpleDBException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, "Invalid token");
            }};
        }

        if (!token.isPrivileged()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return new HashMap<String,Object>() {{
                put(ERR_MSG_FIELD, "Forbidden");
            }};
        }

        //TODO: add logic to verify the message cap per channel has not been exceeded
        // and confirm what to do if the Nth message went over the limit but the first N-1
        // messages succeeded in one request...

        // analyze each message for proper bus
        List<Map<String,Object>> msgs = messages.get("messages");
        for(Map<String,Object> messageData : msgs) {
            BackplaneMessage message = new BackplaneMessage(messageData);
            if (!token.isAllowedBus(message.get(BackplaneMessage.Field.BUS))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return new HashMap<String,Object>() {{
                    put(ERR_MSG_FIELD, "Invalid bus in message");
                }};
            }

            // return an error if the channel used in the message is not associated with a token
            if (daoFactory.getTokenDao().retrieveTokenByChannel(message.get(BackplaneMessage.Field.CHANNEL)) == null) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return new HashMap<String,Object>() {{
                    put(ERR_MSG_FIELD, "Invalid channel in message");
                }};
            }
        }

        // do it all again and store the messages in the db
        BackplaneMessageDAO bmd = daoFactory.getBackplaneMessageDAO();
        for(Map<String,Object> messageData : msgs) {
            bmd.persistBackplaneMessage(new BackplaneMessage(messageData));
        }

        response.setStatus(HttpServletResponse.SC_CREATED);
        return null;

    }

    @ExceptionHandler
    public ModelAndView handleOauthAuthzError(final OAuth2AuthorizationException e) {
        return authzRequestError(e.getOauthErrorCode(), e.getMessage(), e.getRedirectUri(), e.getState());
    }
    
    /**
     * Handle auth errors
     */
    @ExceptionHandler
    @ResponseBody
    public Map<String, String> handle(final AuthException e, HttpServletResponse response) {
        logger.error("Backplane authentication error: " + bpConfig.getDebugException(e));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return new HashMap<String,String>() {{
            put(ERR_MSG_FIELD, e.getMessage());
        }};
    }

    /**
     * Handle all other errors
     */
    @ExceptionHandler
    @ResponseBody
    public Map<String, String> handle(final Exception e, HttpServletResponse response) {
        logger.error("Error handling backplane request", bpConfig.getDebugException(e));
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return new HashMap<String,String>() {{
            try {
                put(ERR_MSG_FIELD, bpConfig.isDebugMode() ? e.getMessage() : "Error processing request.");
            } catch (SimpleDBException e1) {
                put(ERR_MSG_FIELD, "Error processing request.");
            }
        }};
    }


    /*
    public static String randomString(int length) {
        byte[] randomBytes = new byte[length];
        // the base64 character set per RFC 4648 with last two members '-' and '_' removed due to possible
        // compatibility issues.
        byte[] digits = {'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T',
                         'U','V','W','X','Y','Z','a','b','c','d','e','f','g','h','i','j','k','l','m','n',
                         'o','p','q','r','s','t','u','v','w','x','y','z','0','1','2','3','4','5','6','7',
                         '8','9'};
        random.nextBytes(randomBytes);
        for (int i = 0; i < length; i++) {
            byte b = randomBytes[i];
            int c = Math.abs(b % digits.length);
            randomBytes[i] = digits[c];
        }
        try {
            return new String(randomBytes, "US-ASCII");
        }
        catch (UnsupportedEncodingException e) {
            logger.error("US-ASCII character encoding not supported", e); // shouldn't happen
            return null;
        }
    }
    */
    
    // - PRIVATE

    private static final Logger logger = Logger.getLogger(Backplane2Controller.class);

    private static final String ERR_MSG_FIELD = "error";
    private static final String ERR_MSG_DESCRIPTION = "error_description";

    private static final String BUS_OWNER_AUTH_FORM_JSP = "bus_owner_auth";
    private static final String CLIENT_AUTHORIZATION_FORM_JSP = "client_authorization";

    private static final int AUTH_SESSION_COOKIE_LENGTH = 30;
    private static final String AUTH_SESSION_COOKIE = "bp2.bus.owner.auth";
    private static final int AUTHORIZATION_REQUEST_COOKIE_LENGTH = 30;
    private static final String AUTHORIZATION_REQUEST_COOKIE = "bp2.authorization.request";

    private static final String AUTHZ_DECISION_KEY = "bp2_authz_key";

    private final MeterMetric posts =
            Metrics.newMeter(Backplane2Controller.class, "post", "posts", TimeUnit.MINUTES);

    private final MeterMetric channelGets =
            Metrics.newMeter(Backplane2Controller.class, "channel_get", "channel_gets", TimeUnit.MINUTES);
    private final MeterMetric channelGetsSticky = Metrics.newMeter(Backplane2Controller.class, "channel_gets_sticky", "channel_gets_sticky", TimeUnit.MINUTES);


    private final MeterMetric busGets =
            Metrics.newMeter(Backplane2Controller.class, "bus_get", "bus_gets", TimeUnit.MINUTES);
    private final MeterMetric busGetsSticky = Metrics.newMeter(Backplane2Controller.class, "bus_gets_sticky", "bus_gets_sticky", TimeUnit.MINUTES);

    private final TimerMetric getMessagesTime =
            Metrics.newTimer(Backplane2Controller.class, "get_messages_time", TimeUnit.MILLISECONDS, TimeUnit.MINUTES);

    private final HistogramMetric payLoadSizesOnGets = Metrics.newHistogram(Backplane2Controller.class, "payload_sizes_gets");

    private final HistogramMetric messagesPerChannel = Metrics.newHistogram(Backplane2Controller.class, "messages_per_channel");

    @Inject
    private Backplane2Config bpConfig;

    @Inject
    private DaoFactory daoFactory;

    @Inject
    private MetricsAccumulator metricAccumulator;

    //private static final Random random = new SecureRandom();

    private void checkBusOwnerAuth(String busOwner, String password) throws AuthException {
        User busOwnerEntry = null;
        try {
            busOwnerEntry = daoFactory.getBusOwnerDAO().retrieveBusOwner(busOwner);
        } catch (SimpleDBException e) {
            authError("Error looking up bus owner user: " + busOwner);
        }

        if (busOwnerEntry == null) {
            authError("Bus owner user not found: " + busOwner);
        } else if ( ! HmacHashUtils.checkHmacHash(password, busOwnerEntry.get(User.Field.PWDHASH)) ) {
            authError("Incorrect password for bus owner user " + busOwner);
        }
        logger.info("Authenticated bus owner: " + busOwner);
    }

    private void persistAuthenticatedSession(HttpServletResponse response, String busOwner) throws SimpleDBException {
        String authCookie = ChannelUtil.randomString(AUTH_SESSION_COOKIE_LENGTH);
        daoFactory.getAuthSessionDAO().persistAuthSession(new AuthSession(busOwner, authCookie));
        response.addCookie(new Cookie(AUTH_SESSION_COOKIE, authCookie));
    }

    private String getAuthenticatedBusOwner(HttpServletRequest request, String authSessionCookie) {
        if (authSessionCookie == null) return null;
        try {
            AuthSession authSession = daoFactory.getAuthSessionDAO().retrieveAuthSession(authSessionCookie);
            String authenticatedOwner = authSession.get(AuthSession.Field.AUTH_USER);
            logger.info("Session found for previously authenticated bus owner: " + authenticatedOwner);
            return authenticatedOwner;
        } catch (SimpleDBException e) {
            return null;
        }
    }

    private void authError(String errMsg) throws AuthException {
        logger.error(errMsg);
        try {
            throw new AuthException("Access denied. " + (bpConfig.isDebugMode() ? errMsg : ""));
        } catch (Exception e) {
            throw new AuthException("Access denied.");
        }
    }

    private String paddedResponse(String callback, String s) {
        if (StringUtils.isBlank(callback)) {
            throw new IllegalArgumentException("Callback cannot be blank.");
        }
        StringBuilder result = new StringBuilder(callback);
        result.append("(").append(s).append(")");
        return result.toString();
    }

    /** Parse, extract & validate an OAuth2 authorization request from the HTTP request and basic auth header */
    private AuthorizationRequest parseAuthZrequest(HttpServletRequest request, String basicAuth) throws OAuth2AuthorizationException {
        try {
            // authenticate client
            Client authenticatedClient = getAuthenticatedClient(basicAuth);
            // parse authz request
            AuthorizationRequest authorizationRequest = new AuthorizationRequest(
                    ChannelUtil.randomString(AUTHORIZATION_REQUEST_COOKIE_LENGTH),
                    authenticatedClient.get(Client.ClientField.REDIRECT_URI),
                    request.getParameterMap());
            // check auth client_id == request param client_id
            String requestClient = authorizationRequest.get(AuthorizationRequest.Field.CLIENT_ID);
            if ( ! requestClient.equals(authenticatedClient.get(Client.Field.USER))) {
                throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_INVALID_REQUEST, "Mismatched client_id in request and basicauth header.", request);
            }
            logger.info("Parsed authorization request: " + authorizationRequest);
            return authorizationRequest;
        } catch (AuthException e) {
            throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_ACCESS_DENIED, "Client authentication failed.", request);
        } catch (Exception e) {
            throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_INVALID_REQUEST, e.getMessage(), request, e);
        }
    }

    private Client getAuthenticatedClient(String basicAuth) throws AuthException {
        String userPass = null;
        if (basicAuth == null || !basicAuth.startsWith("Basic ") || basicAuth.length() < 7) {
            authError("Invalid client authorization header: " + basicAuth);
        } else {
            try {
                userPass = new String(Base64.decodeBase64(basicAuth.substring(6).getBytes("utf-8")));
            } catch (UnsupportedEncodingException e) {
                authError("Cannot check client authentication, unsupported encoding: utf-8"); // shouldn't happen
            }
        }

        @SuppressWarnings({"ConstantConditions"})
        int delim = userPass.indexOf(":");
        if (delim == -1) {
            authError("Invalid Basic auth token: " + userPass);
        }
        String client = userPass.substring(0, delim);
        String pass = userPass.substring(delim + 1);

        Client clientEntry = null;
        try {
            clientEntry = daoFactory.getClientDAO().retrieveClient(client);
        } catch (SimpleDBException e) {
            authError("Error looking up client: " + client);
        }

        if (clientEntry == null) {
            authError("Client not found: " + client);
        } else if (!HmacHashUtils.checkHmacHash(pass, clientEntry.get(Client.Field.PWDHASH))) {
            authError("Incorrect password for client " + client);
        }

        logger.info("Authenticated client: " + client);
        return clientEntry;
    }

    /** Present an authorization form to the bus owner and obtain authorization decision */
    private ModelAndView processAuthZrequest(AuthorizationRequest authzRequest, String authSessionCookie, String authenticatedBusOwner) throws OAuth2AuthorizationException {
        Map<String,String> model = new HashMap<String, String>();

        // generate & persist authZdecisionKey
        try {
            AuthorizationDecisionKey authorizationDecisionKey = new AuthorizationDecisionKey(authSessionCookie);
            daoFactory.getAuthorizationDecisionKeyDAO().persistAuthorizationDecisionKey(authorizationDecisionKey);

            model.put("auth_key", authorizationDecisionKey.get(AuthorizationDecisionKey.Field.KEY));
            model.put(AuthorizationRequest.Field.CLIENT_ID.getFieldName(), authzRequest.get(AuthorizationRequest.Field.CLIENT_ID));
            model.put(AuthorizationRequest.Field.REDIRECT_URI.getFieldName(), authzRequest.get(AuthorizationRequest.Field.REDIRECT_URI));

            String scope = authzRequest.get(AuthorizationRequest.Field.SCOPE);
            List<BusConfig2> ownedBuses = daoFactory.getBusDao().retrieveBuses(authenticatedBusOwner);
            model.put(AuthorizationRequest.Field.SCOPE.getFieldName(), checkScope(scope, ownedBuses) );

            // return authZ form
            logger.info("Requesting bus owner authorization for :" + authzRequest.get(AuthorizationRequest.Field.CLIENT_ID) +
                    "[" + authzRequest.get(AuthorizationRequest.Field.COOKIE)+"]");
            return new ModelAndView(CLIENT_AUTHORIZATION_FORM_JSP, model);

        } catch (SimpleDBException e) {
            throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_SERVER_ERROR, e.getMessage(), authzRequest, e);
        }
    }

    private String checkScope(String scope, List<BusConfig2> ownedBuses) {
        StringBuilder result = new StringBuilder();
        if(StringUtils.isEmpty(scope)) {
            // request scope empty, ask/offer permission to all owned buses
            for(BusConfig2 bus : ownedBuses) {
                result.append("bus:").append(bus.get(BusConfig2.Field.BUS_NAME)).append(" ");
            }
            if(result.length() > 0) {
                result.deleteCharAt(result.length()-1);
            }
        } else {
            List<String> ownedBusNames = new ArrayList<String>();
            for(BusConfig2 bus : ownedBuses) {
                ownedBusNames.add(bus.get(BusConfig2.Field.BUS_NAME));
            }
            for(String scopeToken : scope.split(" ")) {
                if(scopeToken.startsWith("bus:")) {
                    String bus = scopeToken.substring(4);
                    if (! ownedBusNames.contains(bus) ) continue;
                }
                result.append(scopeToken).append(" ");
            }
            if(result.length() > 0) {
                result.deleteCharAt(result.length()-1);
            }
        }

        String resultString = result.toString();
        if (! resultString.equals(scope)) {
            logger.info("Checked scope: " + resultString);
        }
        return resultString;
    }

    private ModelAndView processAuthZdecision(String authZdecisionKey, String authSessionCookie,
                                              String authenticatedBusOwner,
                                              String authorizationRequestCookie, HttpServletRequest request) throws OAuth2AuthorizationException {
        AuthorizationRequest authorizationRequest = null;
        try {
            // retrieve authorization request
            authorizationRequest = daoFactory.getAuthorizationRequestDAO().retrieveAuthorizationRequest(authorizationRequestCookie);

            // check authZdecisionKey
            AuthorizationDecisionKey authZdecisionKeyEntry = daoFactory.getAuthorizationDecisionKeyDAO().retrieveAuthorizationRequest(authZdecisionKey);
            if (null == authZdecisionKeyEntry || ! authSessionCookie.equals(authZdecisionKeyEntry.get(AuthorizationDecisionKey.Field.AUTH_COOKIE))) {
                throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_ACCESS_DENIED, "Presented authorization key was issued to a different authenticated bus owner.", authorizationRequest);
            }

            if (! "Authorize".equals(request.getParameter("authorize"))) {
                throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_ACCESS_DENIED, "Bus owner denied authorization.", authorizationRequest);
            } else {
                // create grant/code
                Grant grant = new Grant(
                        authorizationRequest.get(AuthorizationRequest.Field.CLIENT_ID),
                        // todo: use (and check) scope posted back by bus owner
                        getBuses(checkScope(
                                authorizationRequest.get(AuthorizationRequest.Field.SCOPE),
                                daoFactory.getBusDao().retrieveBuses(authenticatedBusOwner)))
                );

                grant.setCodeIssuedNow();
                daoFactory.getGrantDao().persistGrant(grant);
                
                logger.info("Authorized " + authorizationRequest.get(AuthorizationRequest.Field.CLIENT_ID)+
                        "[" + authorizationRequest.get(AuthorizationRequest.Field.COOKIE)+"]" + "grant ID: " + grant.getIdValue());

                // return OAuth2 authz response
                final String code = grant.getIdValue();
                final String state = authorizationRequest.get(AuthorizationRequest.Field.STATE);

                try {
                    return new ModelAndView("redirect:" + UrlResponseFormat.QUERY.encode(
                            authorizationRequest.get(AuthorizationRequest.Field.REDIRECT_URI),
                            new HashMap<String, String>() {{
                                put(OAuth2.OAUTH2_AUTHZ_RESPONSE_CODE, code);
                                if (StringUtils.isNotEmpty(state)) {
                                    put(OAuth2.OAUTH2_AUTHZ_RESPONSE_STATE, state);
                                }
                            }}));
                } catch (ValidationException ve) {
                    String errMsg = "Error building (positive) authorization response: " + ve.getMessage();
                    logger.error(errMsg, ve);
                    return authzRequestError(ve.getCode(), errMsg,
                            authorizationRequest.get(AuthorizationRequest.Field.REDIRECT_URI),
                            authorizationRequest.get(AuthorizationRequest.Field.STATE));
                }
            }
        } catch (SimpleDBException e) {
            throw new OAuth2AuthorizationException(OAuth2.OAUTH2_AUTHZ_SERVER_ERROR, e.getMessage(), authorizationRequest, e);
        }
    }

    private String getBuses(String scope) {
        if (StringUtils.isEmpty(scope)) {
            return "";
        } else {
            StringBuilder result = new StringBuilder();
            for(String scopeToken : scope.split(" ")) {
                if(scopeToken.startsWith("bus:")) {
                    result.append(scopeToken.substring(4)).append(" ");
                }
            }
            if (result.length() > 0) {
                result.deleteCharAt(result.length()-1);
            }
            return result.toString();
        }
    }
    
    private static ModelAndView authzRequestError( final String oauthErrCode, final String errMsg,
                                                   final String redirectUri, final String state) {
        // direct or in/redirect
        if (OAuth2.OAUTH2_AUTHZ_DIRECT_ERROR.equals(oauthErrCode)) {
            return new ModelAndView(DIRECT_RESPONSE, new HashMap<String, Object>() {{
                put("DIRECT_RESPONSE", errMsg);
            }});
        } else {
            try {
                return new ModelAndView("redirect:" + UrlResponseFormat.QUERY.encode(
                        redirectUri,
                        new HashMap<String, String>() {{
                            put(OAuth2.OAUTH2_AUTHZ_ERROR_FIELD_NAME, oauthErrCode);
                            put(OAuth2.OAUTH2_AUTHZ_ERROR_DESC_FIELD_NAME, errMsg);
                            if (StringUtils.isNotEmpty(state)) {
                                put(AuthorizationRequest.Field.STATE.getFieldName(), state);
                            }
                        }}));

            } catch (ValidationException e) {
                logger.error("Error building redirect_uri: " + e.getMessage());
                return new ModelAndView(DIRECT_RESPONSE, new HashMap<String, Object>() {{
                    put("DIRECT_RESPONSE", errMsg);
                }});
            }
        }
    }
}
