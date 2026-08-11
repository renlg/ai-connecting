package com.aiconnecting.repository;

import com.aiconnecting.entity.ChannelHealth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChannelHealthRepository extends JpaRepository<ChannelHealth, Long> {

    Optional<ChannelHealth> findByChannelId(Long channelId);

    void deleteByChannelId(Long channelId);
}
