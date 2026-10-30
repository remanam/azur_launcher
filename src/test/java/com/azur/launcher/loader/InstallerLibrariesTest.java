package com.azur.launcher.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstallerLibrariesTest {

    @Test
    void takesLibrariesWithAnUrlAndSkipsBundledOnes() {
        String json = """
            {"libraries":[
              {"name":"a:b:1","downloads":{"artifact":{"path":"a/b/1/b-1.jar","url":"https://maven.neoforged.net/releases/a/b/1/b-1.jar","sha1":"abc","size":5}}},
              {"name":"c:d:2","downloads":{"artifact":{"path":"c/d/2/d-2.jar","url":"","sha1":"def","size":7}}},
              {"name":"e:f:3"}
            ]}""";
        Map<String, InstallerLibraries.Item> out = new LinkedHashMap<>();
        InstallerLibraries.collect(JsonParser.parseString(json), out);
        assertEquals(1, out.size());
        InstallerLibraries.Item item = out.get("a/b/1/b-1.jar");
        assertEquals("abc", item.sha1());
        assertEquals(5, item.size());
    }
}
