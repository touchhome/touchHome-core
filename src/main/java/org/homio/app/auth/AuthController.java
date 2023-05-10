package org.homio.app.auth;

import static java.lang.String.format;

import java.security.Principal;
import javax.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.homio.app.config.AppProperties;
import org.homio.app.manager.common.EntityContextImpl;
import org.homio.app.model.entity.user.UserBaseEntity;
import org.homio.app.setting.system.SystemLogoutButtonSetting;
import org.homio.bundle.api.EntityContext;
import org.homio.bundle.api.entity.UserEntity;
import org.homio.bundle.api.entity.UserEntity.UserType;
import org.homio.bundle.api.ui.UI.Color;
import org.homio.bundle.api.util.CommonUtils;
import org.json.JSONObject;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Log4j2
@RestController
@RequestMapping("/rest/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final EntityContext entityContext;
    private final AppProperties appProperties;

    @GetMapping("/status")
    public StatusResponse getStatus(Principal user) {
        if (user == null) {
            return new StatusResponse(401, null);
        }
        String email = (String) ((UsernamePasswordAuthenticationToken) user).getDetails();
        addUserNotificationBlock(email, false);
        String version = format("%s-%s-%s", appProperties.getVersion(), EntityContextImpl.BUNDLE_UPDATE_COUNT, CommonUtils.RUN_COUNT);
        return new StatusResponse(200, version);
    }

    @GetMapping("/user")
    public UserEntity getUser() {
        return entityContext.getUser();
    }

    @PostMapping("/login")
    public String login(@Valid @RequestBody LoginRequest credentials) {
        UserBaseEntity.log.info("Login <{}>", credentials.getEmail());
        try {
            String username = credentials.getEmail();
            val user = new UsernamePasswordAuthenticationToken(username, credentials.getPassword());
            Authentication authentication = authenticationManager.authenticate(user);
            UserBaseEntity.log.info("Login success for <{}>", credentials.getEmail());
            addUserNotificationBlock(username, true);
            return jwtTokenProvider.createToken(username, authentication);
        } catch (Exception ex) {
            UserBaseEntity.log.info("Login failed for <{}>", credentials.getEmail(), ex);
            throw ex;
        }
    }

    private void addUserNotificationBlock(String username, boolean replace) {
        String key = "user-" + username;
        if (replace || !entityContext.ui().isHasNotificationBlock(key)) {
            entityContext.ui().addNotificationBlock(key, key, "fas fa-user", "#AAAC2C", builder ->
                builder.addButtonInfo("", "", "", Color.RED,
                    "fas fa-right-from-bracket", "W.INFO.LOGOUT",
                    "W.CONFIRM.LOGOUT", (ignore, params) -> {
                        entityContext.setting().setValue(SystemLogoutButtonSetting.class, new JSONObject());
                        return null;
                    }));
        }
    }

    @Getter
    @Setter
    public static class LoginRequest {

        private String email;
        private String password;
    }

    @Getter
    @Setter
    public static class UserRequest extends LoginRequest {

        private String name;
        private UserType userType;
    }

    @Getter
    @RequiredArgsConstructor
    public static class StatusResponse {

        private final int status;
        private final String version;
    }
}
