package com.aiconnecting.repository;

import com.aiconnecting.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<Token, Long> {
    Optional<Token> findByTokenKey(String tokenKey);
    List<Token> findByUserId(Long userId);

    /** 历史明文 tokenKey（sk- 前缀）行，供启动时迁移为哈希存储 */
    List<Token> findByTokenKeyStartingWith(String prefix);

    @Query("SELECT t FROM Token t ORDER BY t.createdAt DESC")
    List<Token> findAllOrderByCreatedAtDesc();

    @Query("SELECT t FROM Token t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
    List<Token> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("SELECT t.id FROM Token t WHERE t.userId = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(COUNT(t), 0), COALESCE(SUM(t.usedQuota), 0) FROM Token t WHERE t.userId = :userId")
    List<Object[]> sumStatsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Token t SET t.usedQuota = t.usedQuota + :delta WHERE t.id = :tokenId")
    void addUsedQuota(@org.springframework.data.repository.query.Param("tokenId") Long tokenId, @org.springframework.data.repository.query.Param("delta") long delta);
}
