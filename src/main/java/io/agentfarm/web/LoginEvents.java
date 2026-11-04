package io.agentfarm.web;

import io.agentfarm.core.LoginThrottle;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Counts what Spring Security decides about each password, in the form and in HTTP Basic alike. */
@Component
class LoginEvents {
  private final LoginThrottle throttle;

  LoginEvents(LoginThrottle throttle) {
    this.throttle = throttle;
  }

  @EventListener
  void failed(AuthenticationFailureBadCredentialsEvent event) {
    throttle.failed(key(event.getAuthentication()));
  }

  @EventListener
  void succeeded(AuthenticationSuccessEvent event) {
    throttle.succeeded(key(event.getAuthentication()));
  }

  private static String key(Authentication authentication) {
    String address =
        authentication.getDetails() instanceof WebAuthenticationDetails details
            ? details.getRemoteAddress()
            : "";
    return LoginThrottleFilter.key(authentication.getName(), address);
  }
}
