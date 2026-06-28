package com.aiconnecting.repository;

import com.aiconnecting.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<Token, Long> {
    Optional<Token> findByTokenKey(String tokenKey);
    List<Token> findByUserId(Long userId);
    boolean existsByTokenKey(String tokenKey);
}
