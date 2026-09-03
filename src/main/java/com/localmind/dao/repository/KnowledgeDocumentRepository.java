package com.localmind.dao.repository;

import com.localmind.dao.entity.KnowledgeDocument;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from KnowledgeDocument document where document.id = :id")
    Optional<KnowledgeDocument> findByIdForUpdate(@Param("id") long id);
}