package org.nexus.consortium.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = HeaderAdapter.TABLE_HEADER, indexes = {
        @Index(name = "block_hash_index", columnList = HeaderAdapter.COLUMN_HASH),
        @Index(name = "hash_prev_index", columnList = HeaderAdapter.COLUMN_HASH_PREV),
        @Index(name = "height_index", columnList = HeaderAdapter.COLUMN_HEIGHT),
        @Index(name = "created_at_index", columnList = HeaderAdapter.COLUMN_CREATED_AT)
})
public class Header extends HeaderAdapter {
}
