package com.aiconnecting.repository;

import com.aiconnecting.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<Token, Long> {
    Optional<Token> findByTokenKey(String tokenKey);
    List<Token> findByUserId(Long userId);
    boolean existsByTokenKey(String tokenKey);

    @Modifying
    @Query("UPDATE Token t SET t.usedQuota = t.usedQuota + :delta WHERE t.id = :tokenId")
    void addUsedQuota(@org.springframework.data.repository.query.Param("tokenId") Long tokenId, @org.springframework.data.repository.query.Param("delta") long delta);
}
