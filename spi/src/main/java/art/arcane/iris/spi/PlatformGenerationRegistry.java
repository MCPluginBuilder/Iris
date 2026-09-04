package art.arcane.iris.spi;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public interface PlatformGenerationRegistry {
    Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    String runtimeIdentity();

    String generatedDefinitionRendererIdentity();

    default String customBiomeResourceKey(String identitySha256) {
        return contentAddressedCustomBiomeResourceKey(identitySha256);
    }

    default List<String> legacyCustomBiomeResourceKeys(
            String packName,
            String dimensionKey,
            String customBiomeId
    ) {
        return List.of();
    }

    String dimensionTypeResourceKey(String packName, String dimensionKey, String dimensionTypeKey);

    Definition definition(String registryKey, String resourceKey);

    default Definition generatedDefinition(String registryKey, String resourceKey) {
        return definition(registryKey, resourceKey);
    }

    default Definition canonicalDefinition(String registryKey, String resourceKey, String sourceJson) {
        return Definition.exactJson(sourceJson);
    }

    static String contentAddressedCustomBiomeResourceKey(String identitySha256) {
        String requiredFingerprint = Objects.requireNonNull(identitySha256, "identitySha256");
        if (!SHA_256_PATTERN.matcher(requiredFingerprint).matches()) {
            throw new IllegalArgumentException("Custom biome identity fingerprint must be a lowercase SHA-256 value.");
        }
        return "iris:biomes/" + requiredFingerprint;
    }

    enum Encoding {
        JSON,
        CONSERVATIVE_IDENTITY
    }

    record Definition(Encoding encoding, String value) {
        public Definition {
            encoding = Objects.requireNonNull(encoding, "encoding");
            value = Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Platform registry definition must not be blank.");
            }
        }

        public static Definition exactJson(String json) {
            return new Definition(Encoding.JSON, json);
        }

        public static Definition conservativeIdentity(String identity) {
            return new Definition(Encoding.CONSERVATIVE_IDENTITY, identity);
        }

        public static Definition resourceIdentity(String registryKey, String resourceKey) {
            String requiredRegistryKey = requireText(registryKey, "registryKey");
            String requiredResourceKey = requireText(resourceKey, "resourceKey");
            return conservativeIdentity(
                    "registry-resource-key-v1|" + requiredRegistryKey + "|" + requiredResourceKey
            );
        }

        private static String requireText(String value, String label) {
            String required = Objects.requireNonNull(value, label).trim();
            if (required.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank.");
            }
            return required;
        }
    }
}
