package com.xd.rulescript.repository;

import com.xd.rulescript.entity.Conversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByRuleId(Long ruleId);
}
