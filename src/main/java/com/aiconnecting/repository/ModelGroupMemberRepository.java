package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelGroupMemberRepository extends JpaRepository<ModelGroupMember, Long> {

    List<ModelGroupMember> findByGroupIdOrderBySortOrderAsc(Long groupId);

    List<ModelGroupMember> findByModelConfigId(Long modelConfigId);

    void deleteByGroupId(Long groupId);

    void deleteByGroupIdAndModelConfigId(Long groupId, Long modelConfigId);

    long countByGroupId(Long groupId);

    boolean existsByGroupIdAndModelConfigId(Long groupId, Long modelConfigId);
}
