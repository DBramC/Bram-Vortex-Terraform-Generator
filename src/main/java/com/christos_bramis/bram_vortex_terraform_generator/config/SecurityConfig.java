package com.christos_bramis.bram_vortex_terraform_generator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Απενεργοποίηση CSRF για να δουλεύουν τα POST requests (όπως το /generate)
                .csrf(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // Επιτρέπουμε τα public endpoints που έρχονται μέσω Kong
                        .requestMatchers("/terraform/status/**").permitAll()
                        .requestMatchers("/terraform/download/**").permitAll()

                        // Επιτρέπουμε το /generate (θα καλείται μόνο εσωτερικά αφού δεν υπάρχει στο Ingress)
                        .requestMatchers("/terraform/generate/**").permitAll()

                        .anyRequest().authenticated()
                )

                // Stateless διαχείριση για Microservices
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }
}