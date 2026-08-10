package org.nexus.consortium;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.nexus.consortium.dao.BlockDao;
import org.nexus.consortium.service.BlockRepositoryService;

import jakarta.annotation.PostConstruct;

@Component
@Slf4j
public class SimpleBean {

    @Autowired
    private BlockDao blockDao;

    @Autowired
    private BlockRepositoryService blockStoreService;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() throws JsonProcessingException {
        log.info("config loaded success");
    }
}
