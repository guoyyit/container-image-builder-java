package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseImageTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void buildsOnTopOfLocalBaseImage() throws Exception {
        Path baseContext = createContext("base-ctx");
        writeFile(baseContext, "etc/version", "1");
        Path baseDescriptor = createDescriptor("base.desc",
                "FROM scratch\nCOPY etc /etc\n");
        BuildResult base = service.build(new BuildRequest("base:1", baseDescriptor, baseContext));

        Path appContext = createContext("app-ctx");
        writeFile(appContext, "bin/app", "app-bytes");
        Path appDescriptor = createDescriptor("app.desc",
                "FROM base:1\nCOPY bin /bin\n");
        BuildResult app = service.build(new BuildRequest("app", appDescriptor, appContext));

        assertEquals(1, app.layerChainIds().size());
        assertEquals(base.layerChainIds().get(0), service.inspect("app").baseChainId());

        BuildResult appAgain = service.build(new BuildRequest("app", appDescriptor, appContext));
        assertEquals(app.imageId(), appAgain.imageId());
        assertTrue(appAgain.cacheHit());
    }

    @Test
    void changedBaseImageRebuildsDependentLayer() throws Exception {
        Path baseContext = createContext("base-ctx");
        writeFile(baseContext, "etc/version", "1");
        Path baseDescriptor = createDescriptor("base.desc",
                "FROM scratch\nCOPY etc /etc\n");
        service.build(new BuildRequest("base", baseDescriptor, baseContext));

        Path appContext = createContext("app-ctx");
        writeFile(appContext, "bin/app", "app-bytes");
        Path appDescriptor = createDescriptor("app.desc",
                "FROM base\nCOPY bin /bin\n");
        BuildResult appFirst = service.build(new BuildRequest("app", appDescriptor, appContext));

        writeFile(baseContext, "etc/version", "2");
        BuildResult baseUpdated = service.build(new BuildRequest("base", baseDescriptor, baseContext));
        BuildResult appSecond = service.build(new BuildRequest("app", appDescriptor, appContext));

        assertNotEquals(appFirst.layerChainIds().get(0), appSecond.layerChainIds().get(0));
        assertNotEquals(appFirst.imageId(), appSecond.imageId());
        assertEquals(baseUpdated.layerChainIds().get(0), service.inspect("app").baseChainId());
    }

    @Test
    void missingBaseImageFailsBuild() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "f.txt", "x");
        Path descriptor = createDescriptor("app.desc",
                "FROM does-not-exist\nCOPY f.txt /f.txt\n");
        assertThrows(Exception.class, () ->
                service.build(new BuildRequest("app", descriptor, context)));
    }
}
