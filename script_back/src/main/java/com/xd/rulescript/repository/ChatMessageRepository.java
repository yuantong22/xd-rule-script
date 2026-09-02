package com.xd.rulescript.repository;

import com.xd.rulescript.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 同一秒内落库的消息靠 id 兜底排序，保证回显顺序稳定 */
    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    void deleteByConversationId(Long conversationId);
}
