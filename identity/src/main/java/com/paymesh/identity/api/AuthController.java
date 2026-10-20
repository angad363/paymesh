package com.paymesh.identity.api;

import com.paymesh.identity.application.AuthenticationService;
import com.paymesh.identity.application.IssuedTokens;
import com.paymesh.identity.application.LoginCommand;
import com.paymesh.identity.application.RegisterUserCommand;
import com.paymesh.identity.application.RegisterUserService;
import com.paymesh.identity.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (SDD 8.3), under the {@code api/v1} prefix the merchant
 * API already uses -- the SDD writes them as {@code /v1/auth/...}, and matching the
 * existing code wins.
 *
 * <p>All four are public: nothing enforces access tokens yet, by design. Applying
 * them to the rest of the API is the next change.
 */
@RestController
@RequestMapping("api/v1/auth")
public final class AuthController {

    private final RegisterUserService registerUserService;

    private final AuthenticationService authenticationService;

    public AuthController(
        RegisterUserService registerUserService,
        AuthenticationService authenticationService
    ) {
        this.registerUserService = registerUserService;
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(
        @Valid @RequestBody RegisterUserRequest request,
        HttpServletRequest httpRequest
    ) {
        RegisterUserCommand command = new RegisterUserCommand(
            request.email(),
            request.password(),
            request.merchantId(),
            httpRequest.getRemoteAddr()
        );

        User user = registerUserService.register(command);

        return UserResponse.from(user);
    }

    @PostMapping("/login")
    TokenResponse login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpRequest
    ) {
        IssuedTokens tokens = authenticationService.login(
            new LoginCommand(
                request.email(),
                request.password(),
                httpRequest.getRemoteAddr()
            )
        );

        return TokenResponse.from(tokens);
    }

    @PostMapping("/token/refresh")
    TokenResponse refresh(
        @Valid @RequestBody RefreshTokenRequest request,
        HttpServletRequest httpRequest
    ) {
        IssuedTokens tokens = authenticationService.refresh(
            request.refreshToken(),
            httpRequest.getRemoteAddr()
        );

        return TokenResponse.from(tokens);
    }

    /**
     * 204 with no body, and the same 204 for a token that was already revoked or
     * never existed: logout is idempotent, so a client retrying after a timeout is
     * not told its request failed, and the endpoint cannot be used to test whether
     * a token is still live.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(
        @Valid @RequestBody RefreshTokenRequest request,
        HttpServletRequest httpRequest
    ) {
        authenticationService.logout(request.refreshToken(), httpRequest.getRemoteAddr());
    }
}
