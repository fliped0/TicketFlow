package com.ticketflow.config;
import com.ticketflow.security.DatabaseJwtAuthenticationConverter;
import com.ticketflow.common.response.ApiResponse;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.util.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.crypto.password.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;
@Configuration
public class SecurityConfiguration {
 @Bean PasswordEncoder passwordEncoder() {
  var pbkdf2=new Pbkdf2PasswordEncoder("",16,310000,Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
  return new DelegatingPasswordEncoder("pbkdf2",Map.of("pbkdf2",pbkdf2));
 }
 private byte[] pem(String path) throws Exception {
  return Base64.getDecoder().decode(Files.readString(Path.of(path)).replaceAll("-----[A-Z ]+-----","").replaceAll("\\s",""));
 }
 @Bean RSAKey signingKey(@Value("${ticketflow.jwt.private-key}") String privatePath,@Value("${ticketflow.jwt.public-key}") String publicPath) throws Exception {
  KeyFactory factory=KeyFactory.getInstance("RSA");
  var privateKey=(RSAPrivateKey)factory.generatePrivate(new PKCS8EncodedKeySpec(pem(privatePath)));
  var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(pem(publicPath)));
  if(!privateKey.getModulus().equals(publicKey.getModulus()) || publicKey.getModulus().bitLength()<2048) throw new IllegalStateException("Invalid JWT RSA key pair");
  return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("ticketflow-v1").build();
 }
 @Bean JwtEncoder jwtEncoder(RSAKey key) {return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));}
 @Bean JwtDecoder jwtDecoder(RSAKey key) throws Exception {
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
  OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience()!=null && jwt.getAudience().contains("ticketflow-api") && jwt.getSubject()!=null && jwt.getExpiresAt()!=null && jwt.getNotBefore()!=null
   ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),new JwtIssuerValidator("ticketflow"),audience));
  return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http,DatabaseJwtAuthenticationConverter authenticationConverter,JsonMapper json) throws Exception {
  http.csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  http.authorizeHttpRequests(auth->auth
   .requestMatchers("/actuator/health").permitAll()
   .requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login").permitAll()
   .requestMatchers(HttpMethod.GET,"/api/v1/events","/api/v1/events/*","/api/v1/events/*/sessions","/api/v1/sessions/*/tiers").permitAll()
   .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated());
  http.oauth2ResourceServer(resource->resource.jwt(jwt->jwt.jwtAuthenticationConverter(authenticationConverter)).authenticationEntryPoint((req,res,e)->{
   res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
   res.getWriter().write(json.writeValueAsString(ApiResponse.error("UNAUTHENTICATED","请先登录")));
  }));
  http.exceptionHandling(errors->errors.authenticationEntryPoint((req,res,e)->{
   res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
   res.getWriter().write(json.writeValueAsString(ApiResponse.error("UNAUTHENTICATED","请先登录")));
  }).accessDeniedHandler((req,res,e)->{
   res.setStatus(403);res.setContentType("application/json;charset=UTF-8");
   res.getWriter().write(json.writeValueAsString(ApiResponse.error("FORBIDDEN","无权访问")));
  }));
  return http.build();
 }
}
