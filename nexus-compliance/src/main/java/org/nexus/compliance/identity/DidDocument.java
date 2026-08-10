package org.nexus.compliance.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DID 文档实体。
 * <p>
 * 符合 W3C DID 规范的最小化文档结构。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DidDocument {

    /** DID */
    @JsonProperty("id")
    private String id;

    /** 公钥列表 */
    @JsonProperty("publicKeys")
    private List<String> publicKeys;

    /** 认证方式 */
    @JsonProperty("authentication")
    private List<String> authentication;

    /** 服务端点 */
    @JsonProperty("serviceEndpoints")
    private List<String> serviceEndpoints;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public List<String> getPublicKeys() { return publicKeys; }
    public void setPublicKeys(List<String> publicKeys) { this.publicKeys = publicKeys; }

    public List<String> getAuthentication() { return authentication; }
    public void setAuthentication(List<String> authentication) { this.authentication = authentication; }

    public List<String> getServiceEndpoints() { return serviceEndpoints; }
    public void setServiceEndpoints(List<String> serviceEndpoints) { this.serviceEndpoints = serviceEndpoints; }
}