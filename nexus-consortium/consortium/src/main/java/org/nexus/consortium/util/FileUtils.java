package org.nexus.consortium.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.nexus.consortium.exception.ApplicationException;

public class FileUtils {
    public static Resource getResource(String path) throws ApplicationException {
        Resource resource = new FileSystemResource(path);
        if (!resource.exists()) {
            resource = new ClassPathResource(path);
        }
        if (!resource.exists()) {
            throw new ApplicationException("resource " + path + " not found");
        }
        return resource;
    }
}
