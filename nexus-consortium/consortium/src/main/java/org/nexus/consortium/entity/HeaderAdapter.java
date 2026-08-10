package org.nexus.consortium.entity;

import lombok.*;

import jakarta.persistence.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@MappedSuperclass
public class HeaderAdapter{
    static final String COLUMN_HASH = "block_hash";
    static final String COLUMN_VERSION = "version";
    static final String COLUMN_HASH_PREV = "hash_prev";
    static final String COLUMN_MERKLE_ROOT = "merkle_root";
    static final String COLUMN_HEIGHT = "block_height";
    static final String COLUMN_CREATED_AT = "created_at";
    static final String COLUMN_PAYLOAD = "payload";
    static final String TABLE_HEADER = "header";

    @Id
    @Column(name = COLUMN_HASH, nullable = false)
    private byte[] hash;

    @Column(name = COLUMN_VERSION, nullable = false)
    private int version;

    @Column(name = COLUMN_HASH_PREV, nullable = false)
    private byte[] hashPrev;

    @Column(name = COLUMN_MERKLE_ROOT, nullable = false)
    private byte[] merkleRoot;

    @Column(name = COLUMN_HEIGHT, nullable = false)
    private long height;

    @Column(name = COLUMN_CREATED_AT, nullable = false)
    private long createdAt;

    @Column(name = COLUMN_PAYLOAD, nullable = false)
    private byte[] payload;
}