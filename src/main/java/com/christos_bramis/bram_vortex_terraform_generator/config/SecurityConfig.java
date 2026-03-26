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
                // 1. Απενεργοποίηση CSRF (απαραίτητο για stateless REST APIs)
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Stateless διαχείριση (δεν χρησιμοποιούμε sessions/cookies)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // 3. Ρύθμιση κανόνων πρόσβασης
                .authorizeHttpRequests(auth -> auth
                        // Επιτρέπουμε το webhook από τον Analyzer (εσωτερική επικοινωνία)
                        .requestMatchers("/terraform/generate/**").authenticated()

                        // Τα endpoints για download και status απαιτούν έγκυρο JWT
                        .requestMatchers("/terraform/download/**").authenticated()
                        .requestMatchers("/terraform/status/**").authenticated()

                        // Οτιδήποτε άλλο απαιτεί επίσης login
                        .anyRequest().authenticated()
                )

                // 4. Προσθήκη του JWT Filter ΠΡΙΝ το βασικό φίλτρο της Spring
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}