package art.arcane.iris.core.project;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockData;
import art.arcane.iris.engine.object.IrisExpression;
import art.arcane.iris.engine.object.IrisHydrology;
import art.arcane.iris.engine.object.IrisRiverPolicy;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformRegistries;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisRiverSchemaTest {
    private IrisPlatform previousPlatform;

    @Before
    public void bindPlatform() {
        previousPlatform = IrisPlatforms.isBound() ? IrisPlatforms.get() : null;
        if (previousPlatform != null) {
            IrisPlatforms.unbind();
        }
        IrisPlatform platform = mock(IrisPlatform.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(platform.registries()).thenReturn(registries);
        when(registries.blockTypeKeys()).thenReturn(List.of());
        IrisPlatforms.bind(platform);
    }

    @After
    public void restorePlatform() {
        IrisPlatforms.unbind();
        if (previousPlatform != null) {
            IrisPlatforms.bind(previousPlatform);
        }
    }

    @Test
    public void hydrologySchemaExposesTypedPhysicalHierarchy() {
        JSONObject schema = new SchemaBuilder(IrisHydrology.class, schemaData()).construct();
        JSONObject definitions = schema.getJSONObject("definitions");
        JSONObject properties = schema.getJSONObject("properties");
        JSONObject rivers = referencedProperties(definitions, properties.getJSONObject("rivers"));
        JSONObject routing = referencedProperties(definitions, rivers.getJSONObject("routing"));
        JSONObject branching = referencedProperties(definitions, routing.getJSONObject("branching"));
        JSONObject geometry = referencedProperties(definitions, rivers.getJSONObject("geometry"));
        JSONObject drops = referencedProperties(definitions, geometry.getJSONObject("drops"));
        JSONObject surface = referencedProperties(definitions, rivers.getJSONObject("surface"));
        JSONObject sources = referencedProperties(definitions, surface.getJSONObject("sources"));
        JSONObject channel = referencedProperties(definitions, surface.getJSONObject("channel"));
        JSONObject hydraulics = referencedProperties(definitions, surface.getJSONObject("hydraulics"));
        JSONObject ridgeTunnels = referencedProperties(definitions, surface.getJSONObject("ridgeTunnels"));
        JSONObject mouths = referencedProperties(definitions, surface.getJSONObject("mouths"));
        JSONObject underground = referencedProperties(definitions, rivers.getJSONObject("underground"));
        JSONObject undergroundSources = referencedProperties(definitions, underground.getJSONObject("sources"));
        JSONObject grottos = referencedProperties(definitions, rivers.getJSONObject("grottos"));
        JSONObject coastal = referencedProperties(definitions, grottos.getJSONObject("coastal"));
        JSONObject inland = referencedProperties(definitions, grottos.getJSONObject("inland"));
        JSONObject profiles = rivers.getJSONObject("profiles");
        JSONObject profile = referencedProperties(definitions, profiles.getJSONObject("items"));
        JSONObject deepFluids = properties.getJSONObject("deepFluids");
        JSONObject deepFluid = referencedProperties(definitions, deepFluids.getJSONObject("items"));

        assertEquals(8192, routing.getJSONObject("tileSize").getInt("maximum"));
        assertEquals(64, routing.getJSONObject("refinementSpacing").getInt("maximum"));
        assertEquals(32768, routing.getJSONObject("maximumRouteLength").getInt("maximum"));
        assertEquals(32768, branching.getJSONObject("minimumSurfaceCourseLength").getInt("maximum"));
        assertEquals(32768, branching.getJSONObject("minimumUndergroundCourseLength").getInt("maximum"));
        JSONObject meanders = referencedProperties(definitions, geometry.getJSONObject("meanders"));
        assertEquals(10D, meanders.getJSONObject("maximumTurnDegrees").getDouble("minimum"), 0D);
        assertEquals(List.of("SINKHOLE_GROTTO"),
                enumValues(definitions, routing.getJSONObject("inlandOutlets").getJSONObject("items")));
        assertEquals(64D, sources.getJSONObject("density").getDouble("maximum"), 0D);
        assertEquals(64D, undergroundSources.getJSONObject("density").getDouble("maximum"), 0D);
        assertEquals(8192, sources.getJSONObject("minimumSpacing").getInt("maximum"));
        assertEquals(8192, undergroundSources.getJSONObject("minimumSpacing").getInt("maximum"));
        assertEquals(16, drops.getJSONObject("cascadeRunPerBlock").getInt("maximum"));
        assertEquals(6D, drops.getJSONObject("cascadeExponent").getDouble("maximum"), 0D);
        assertEquals(4, drops.getJSONObject("maximumCascadeStep").getInt("maximum"));
        assertEquals(1D, drops.getJSONObject("flowWidthRatio").getDouble("maximum"), 0D);
        assertEquals(16, drops.getJSONObject("maximumFlowDepth").getInt("maximum"));
        assertEquals(4D, drops.getJSONObject("basinWidthRatio").getDouble("maximum"), 0D);
        assertEquals(32, drops.getJSONObject("maximumBasinDepth").getInt("maximum"));
        assertSnippetBackedObjectReference(channel.getJSONObject("width"));
        assertSnippetBackedObjectReference(channel.getJSONObject("depth"));
        assertSnippetBackedObjectReference(channel.getJSONObject("surfaceInset"));
        assertSnippetBackedObjectReference(channel.getJSONObject("terrainBlendWidth"));
        assertEquals(64, channel.getJSONObject("maximumIncision").getInt("maximum"));
        assertEquals(1D, channel.getJSONObject("shoreWidth").getDouble("minimum"), 0D);
        assertEquals(2D, channel.getJSONObject("shoreWidth").getDouble("maximum"), 0D);
        assertEquals(0, hydraulics.getJSONObject("riffleDrop").getInt("minimum"));
        assertEquals(16, hydraulics.getJSONObject("riffleDrop").getInt("maximum"));
        assertEquals(0, hydraulics.getJSONObject("maximumGradualDrop").getInt("minimum"));
        assertEquals(1024, hydraulics.getJSONObject("maximumGradualLength").getInt("maximum"));
        assertEquals(1, hydraulics.getJSONObject("waterfallMinimumDrop").getInt("minimum"));
        assertEquals(128, hydraulics.getJSONObject("waterfallMinimumDrop").getInt("maximum"));
        assertEquals(4096, ridgeTunnels.getJSONObject("maximumLength").getInt("maximum"));
        assertEquals(0, mouths.getJSONObject("levelingDistance").getInt("minimum"));
        assertEquals(64, mouths.getJSONObject("maximumOceanApron").getInt("maximum"));
        assertSnippetBackedObjectReference(underground.getJSONObject("fluidLevel"));
        assertSnippetBackedObjectReference(underground.getJSONObject("channelWidth"));
        assertSnippetBackedObjectReference(underground.getJSONObject("headroom"));

        assertGrottoBounds(coastal);
        assertGrottoBounds(inland);
        assertEquals("boolean", inland.getJSONObject("connectSurfaceRivers").getString("type"));
        assertEquals("array", profiles.getString("type"));
        assertEquals(1, profiles.getInt("minItems"));
        assertTrue(profile.has("id"));
        assertTrue(profile.has("fluidPalette"));

        assertEquals("array", deepFluids.getString("type"));
        assertTrue(deepFluid.has("id"));
        assertTrue(deepFluid.has("fluidPalette"));
        assertTrue(deepFluid.has("height"));
        assertEquals(64D, deepFluid.getJSONObject("density").getDouble("maximum"), 0D);
        assertEquals(8192, deepFluid.getJSONObject("spacing").getInt("maximum"));
        assertEquals(16, deepFluid.getJSONObject("spacing").getInt("minimum"));
        assertEquals(128, deepFluid.getJSONObject("horizontalRadius").getInt("maximum"));
        assertEquals(2, deepFluid.getJSONObject("horizontalRadius").getInt("minimum"));
        assertEquals(64, deepFluid.getJSONObject("verticalRadius").getInt("maximum"));
        assertEquals(2, deepFluid.getJSONObject("verticalRadius").getInt("minimum"));
        assertEquals(32, deepFluid.getJSONObject("channelWidth").getInt("maximum"));
        assertEquals(32, deepFluid.getJSONObject("depth").getInt("maximum"));
        assertEquals(63, deepFluid.getJSONObject("headroom").getInt("maximum"));
    }

    @Test
    public void riverPolicySchemaKeepsEveryOverlayOptionalAndTyped() {
        JSONObject schema = new SchemaBuilder(IrisRiverPolicy.class, schemaData()).construct();
        JSONObject definitions = schema.getJSONObject("definitions");
        JSONObject properties = schema.getJSONObject("properties");

        assertTrue(!schema.has("required") || schema.getJSONArray("required").length() == 0);
        assertEquals(List.of(
                        "DISABLED",
                        "TRANSIT_ONLY",
                        "NATURAL",
                        "PREFERRED_HEADWATER",
                        "REQUIRED_HEADWATER"
                ),
                enumValues(definitions, properties.getJSONObject("placement")));
        assertEquals(List.of("BLOCK", "AVOID", "ALLOW", "PREFER"),
                enumValues(definitions, properties.getJSONObject("routing")));
        assertEquals("boolean", properties.getJSONObject("outletAdmission").getString("type"));
        assertEquals("array", properties.getJSONObject("profiles").getString("type"));
        assertEquals("array", properties.getJSONObject("surfaceBiomes").getString("type"));
        assertEquals("array", properties.getJSONObject("mouthBiomes").getString("type"));
        assertEquals("array", properties.getJSONObject("shoreBiomes").getString("type"));
        assertEquals("array", properties.getJSONObject("bankBiomes").getString("type"));
        assertEquals("array", properties.getJSONObject("floodedCaveBiomes").getString("type"));
        assertEquals(16D, properties.getJSONObject("widthMultiplier").getDouble("maximum"), 0D);
        assertEquals(0D, properties.getJSONObject("incisionMultiplier").getDouble("minimum"), 0D);
        assertEquals(64D, properties.getJSONObject("routingMultiplier").getDouble("maximum"), 0D);
        assertEquals("#/definitions/erzbiomes",
                properties.getJSONObject("shoreBiomes").getJSONObject("items").getString("$ref"));
    }

    private static void assertGrottoBounds(JSONObject grotto) {
        assertEquals(1, grotto.getJSONObject("horizontalRadius").getInt("minimum"));
        assertEquals(1, grotto.getJSONObject("verticalRadius").getInt("minimum"));
        assertEquals(128, grotto.getJSONObject("horizontalRadius").getInt("maximum"));
        assertEquals(64, grotto.getJSONObject("verticalRadius").getInt("maximum"));
        assertEquals(63, grotto.getJSONObject("headroom").getInt("maximum"));
        assertEquals(1, grotto.getJSONObject("maximumVolume").getInt("minimum"));
        assertEquals(1048576, grotto.getJSONObject("maximumVolume").getInt("maximum"));
    }

    @SuppressWarnings("unchecked")
    private static IrisData schemaData() {
        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisExpression> expressionLoader = mock(ResourceLoader.class);
        ResourceLoader<IrisBlockData> blockLoader = mock(ResourceLoader.class);
        KMap<Class<? extends IrisRegistrant>, ResourceLoader<? extends IrisRegistrant>> loaders = new KMap<>();
        loaders.put(IrisBiome.class, biomeLoader);
        loaders.put(IrisExpression.class, expressionLoader);
        when(data.getBlockLoader()).thenReturn(blockLoader);
        when(data.getLoaders()).thenReturn(loaders);
        when(data.getPossibleSnippets(anyString())).thenReturn(new KList<>());
        when(biomeLoader.getPossibleKeys()).thenReturn(new String[]{"river/surface"});
        when(biomeLoader.getFolderName()).thenReturn("biomes");
        when(biomeLoader.getResourceTypeName()).thenReturn("Biome");
        when(blockLoader.getPossibleKeys()).thenReturn(new String[0]);
        when(expressionLoader.getPossibleKeys()).thenReturn(new String[0]);
        when(expressionLoader.getFolderName()).thenReturn("expressions");
        when(expressionLoader.getResourceTypeName()).thenReturn("Expression");
        return data;
    }

    private static JSONObject referencedProperties(JSONObject definitions, JSONObject reference) {
        String key = reference.getString("$ref").substring("#/definitions/".length());
        return definitions.getJSONObject(key).getJSONObject("properties");
    }

    private static void assertSnippetBackedObjectReference(JSONObject property) {
        JSONArray variants = property.getJSONArray("anyOf");
        boolean hasObjectReference = false;
        boolean hasSnippetReference = false;
        for (int index = 0; index < variants.length(); index++) {
            JSONObject variant = variants.getJSONObject(index);
            if ("object".equals(variant.getString("type")) && variant.has("$ref")) {
                hasObjectReference = true;
            }
            if ("string".equals(variant.getString("type")) && variant.has("$ref")) {
                hasSnippetReference = true;
            }
        }
        assertTrue(hasObjectReference);
        assertTrue(hasSnippetReference);
    }

    private static List<String> enumValues(JSONObject definitions, JSONObject reference) {
        String key = reference.getString("$ref").substring("#/definitions/".length());
        JSONArray values = definitions.getJSONObject(key).getJSONArray("enum");
        List<String> names = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            names.add(values.getString(index));
        }
        return names;
    }
}
