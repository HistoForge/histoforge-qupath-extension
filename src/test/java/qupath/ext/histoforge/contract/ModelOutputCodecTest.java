package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;

class ModelOutputCodecTest {

    @Test
    void explicitChannelNumbersOwnMeaningIndependentOfArrayOrder() {
        var output =
                ModelOutputCodec.parse(
                        JsonParser.parseString(
                                """
                                {
                                  "type": "instance_segmentation_and_classification",
                                  "classChannels": [
                                    {"channel": 3, "role": "emitted", "pathClass": "B"},
                                    {"channel": 1, "role": "training_only", "pathClass": "Other"},
                                    {"channel": 0, "role": "background"},
                                    {"channel": 2, "role": "emitted", "pathClass": "A"}
                                  ]
                                }
                                """));

        assertEquals(
                List.of(
                        new ClassChannel(0, "background", null),
                        new ClassChannel(1, "training_only", "Other"),
                        new ClassChannel(2, "emitted", "A"),
                        new ClassChannel(3, "emitted", "B")),
                output.classChannels());
        assertEquals(List.of("A", "B"), output.emittedClasses());
        assertEquals(List.of("Other", "A", "B"), output.targetClasses());

        assertEquals(
                JsonParser.parseString(
                        """
                        {
                          "type": "instance_segmentation_and_classification",
                          "classChannels": [
                            {"channel": 0, "role": "background"},
                            {"channel": 1, "role": "training_only", "pathClass": "Other"},
                            {"channel": 2, "role": "emitted", "pathClass": "A"},
                            {"channel": 3, "role": "emitted", "pathClass": "B"}
                          ]
                        }
                        """),
                ModelOutputCodec.toJson(output));
    }

    @Test
    void pureInstanceOutputHasOnePostDecodeClassAndNoFakeHead() {
        var output =
                ModelOutputCodec.parse(
                        JsonParser.parseString(
                                """
                                {"type":"instance_segmentation","classes":["Cell"]}
                                """));

        assertEquals(new ModelOutput("instance_segmentation", List.of("Cell"), List.of()), output);
        assertEquals(List.of("Cell"), output.emittedClasses());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelOutputCodec.parse(
                                JsonParser.parseString(
                                        """
                                        {
                                          "type":"instance_segmentation",
                                          "classChannels":[
                                            {"channel":0,"role":"emitted","pathClass":"Cell"}
                                          ]
                                        }
                                        """)));
    }

    @Test
    void invalidClassHeadShapesFailClosed() {
        List<String> invalid =
                List.of(
                        """
                        {"type":"dense_classification","classChannels":[]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":2,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"training_only","pathClass":"Other"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background","pathClass":"Background"},
                          {"channel":1,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":0,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"background"},
                          {"channel":2,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"emitted","pathClass":"Cell"},
                          {"channel":2,"role":"training_only","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"emitted"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"unknown","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":-1,"role":"background"},
                          {"channel":0,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classChannels":[
                          {"channel":0.0,"role":"background"},
                          {"channel":1,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """,
                        """
                        {"type":"dense_classification","classes":["Cell"],"classChannels":[
                          {"channel":0,"role":"background"},
                          {"channel":1,"role":"emitted","pathClass":"Cell"}
                        ]}
                        """);

        invalid.forEach(
                value ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelOutputCodec.parse(JsonParser.parseString(value))));
    }
}
