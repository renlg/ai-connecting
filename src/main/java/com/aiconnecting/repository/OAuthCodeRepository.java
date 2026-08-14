package com.aiconnecting.repository;

import com.aiconnecting.entity.OAuthCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthCodeRepository extends JpaRepository<OAuthCode, String> {

    /** Atomically makes a valid code unusable; exactly one concurrent exchange can succeed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OAuthCode c SET c.used = true WHERE c.code = :code AND c.used = false")
    int markUsed(@Param("code") String code);
}
