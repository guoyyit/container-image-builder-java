package com.github.guoyyit.containerimagebuilderjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "container-builder.cache.root-dir=${java.io.tmpdir}/cib-web-cache"
})
class WebApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void buildInspectGarbageCollectAndDeleteOverHttp() throws Exception {
        Path work = Files.createTempDirectory("cib-web-");
        Path context = work.resolve("ctx");
        Files.createDirectories(context.resolve("app"));
        Files.writeString(context.resolve("app/f.txt"), "payload", StandardCharsets.UTF_8);
        Path descriptor = work.resolve("image.desc");
        Files.writeString(descriptor, "FROM scratch\nCOPY app /app\n", StandardCharsets.UTF_8);

        String requestBody = "{\"imageName\":\"web-demo\",\"descriptorPath\":\""
                + descriptor.toString().replace('\\', '/')
                + "\",\"contextDir\":\""
                + context.toString().replace('\\', '/') + "\"}";

        mockMvc.perform(post("/api/images/build")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").isNotEmpty())
                .andExpect(jsonPath("$.layerChainIds.length()").value(1));

        mockMvc.perform(get("/api/images/web-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("web-demo"))
                .andExpect(jsonPath("$.createdAtEpoch").value(0));

        mockMvc.perform(post("/api/images/gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedLayers").value(0));

        mockMvc.perform(delete("/api/images/web-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void invalidBuildReturnsErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/images/build")
                        .contentType("application/json")
                        .content("{\"imageName\":\"bad\",\"descriptorPath\":\"/nope.desc\","
                                + "\"contextDir\":\"/nope-ctx\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
