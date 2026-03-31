package com.christos_bramis.bram_vortex_terraform_generator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    // Injection του φίλτρου που κάνει το verification
    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Προσθήκη CORS (Πρέπει να είναι πρώτο)
                .cors(cors -> cors.configurationSource(request -> {
                    var opt = new org.springframework.web.cors.CorsConfiguration();
                    opt.setAllowedOrigins(java.util.List.of("http://localhost")); // Ή το domain σου
                    opt.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    opt.setAllowedHeaders(java.util.List.of("*"));
                    opt.setAllowCredentials(true);
                    return opt;
                }))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Βεβαιώσου ότι τα paths εδώ ταιριάζουν με αυτά που στέλνει ο Kong
                        .requestMatchers("/terraform/status/**", "/terraform/download/**").authenticated()
                        .requestMatchers("/terraform/generate/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}