package com.localmind.document;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="knowledge_documents")
public class KnowledgeDocument {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String name;
    private String contentType;
    private long sizeBytes;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status = Status.PROCESSING;
    private int chunkCount;
    @Column(length=1000) private String errorMessage;
    @Column(nullable=false,updatable=false) private Instant createdAt = Instant.now();
    public enum Status { PROCESSING, READY, FAILED }
    public Long getId(){return id;} public String getName(){return name;} public void setName(String v){name=v;}
    public String getContentType(){return contentType;} public void setContentType(String v){contentType=v;}
    public long getSizeBytes(){return sizeBytes;} public void setSizeBytes(long v){sizeBytes=v;}
    public Status getStatus(){return status;} public void setStatus(Status v){status=v;}
    public int getChunkCount(){return chunkCount;} public void setChunkCount(int v){chunkCount=v;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
    public Instant getCreatedAt(){return createdAt;}
}
