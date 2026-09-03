package art.arcane.iris.core.localization;

import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisLanguageEditorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File directory;
    private PluginLanguageService languages;
    private PluginLanguageEditor editor;

    @Before
    public void prepareEditor() throws Exception {
        directory = temporaryFolder.newFolder();
        assertTrue(IrisLanguage.reload(directory, "en_US"));
        Path cache = IrisLanguage.remote(directory).cacheFile("fr_FR");
        Files.createDirectories(cache.getParent());
        Files.copy(Path.of("src/main/resources/languages/fr_FR.json"), cache);
        PluginLanguageEditor.Options options = IrisLanguage.editorOptions();
        LocalizationSnapshot english = LocalizationSnapshot.create(
                LocalizationCandidate.english(IrisLanguage.catalog(), PluralSelector.oneOther()));
        languages = new PluginLanguageService(new PluginLanguageService.Options(
                directory.toPath().resolve("players.properties"), VolmitLocales::all, () -> "en_US", () -> english,
                options.loader()::load, (locale, snapshot) -> {
                    throw new AssertionError("Editing must not select a server language");
                }, Logger.getLogger("IrisLanguageEditorTest")));
        editor = new PluginLanguageEditor(languages, options);
    }

    @After
    public void closeEditor() {
        editor.close();
        languages.close();
        assertTrue(IrisLanguage.reload(directory, "en_US"));
        IrisLanguage.shutdown();
    }

    @Test
    public void savesOneLocaleAndRefreshesItsPersonalSnapshotWithoutSelectingIt() throws Exception {
        UUID player = UUID.randomUUID();
        languages.selectPlayer(player, "fr_FR").get(5, TimeUnit.SECONDS);
        PluginLanguageEditor.Document original = editor.load("fr_FR").get(5, TimeUnit.SECONDS);
        TextValue value = new TextValue("Langue \"{locale}\" rechargee.\nSuite.");
        editor.save(new PluginLanguageEditor.Edit("fr_FR", IrisMessages.COMMAND_RELOAD_SUCCESS.id(),
                original.snapshot().value(IrisMessages.COMMAND_RELOAD_SUCCESS), value)).get(5, TimeUnit.SECONDS);

        Path file = directory.toPath().resolve("languages/overrides/fr_FR.json");
        assertTrue(Files.isRegularFile(file));
        assertEquals(value, IrisLanguage.editorOptions().loader().load("fr_FR").value(IrisMessages.COMMAND_RELOAD_SUCCESS));
        assertEquals(value, languages.snapshot(player).value(IrisMessages.COMMAND_RELOAD_SUCCESS));
        assertEquals("fr_FR", languages.playerLocale(player).orElseThrow());
        assertEquals("en_US", languages.defaultLocale());
        assertEquals("en_US", IrisLanguage.activeLocale());
        assertFalse(Files.exists(directory.toPath().resolve("languages/overrides/en_US.json")));
    }

    @Test
    public void invalidMessageLeavesTheLocaleFileIntact() throws Exception {
        PluginLanguageEditor.Document original = editor.load("fr_FR").get(5, TimeUnit.SECONDS);
        editor.save(new PluginLanguageEditor.Edit("fr_FR", IrisMessages.COMMAND_RELOAD_SUCCESS.id(),
                original.snapshot().value(IrisMessages.COMMAND_RELOAD_SUCCESS), new TextValue("Langue {locale}")))
                .get(5, TimeUnit.SECONDS);
        Path file = directory.toPath().resolve("languages/overrides/fr_FR.json");
        byte[] before = Files.readAllBytes(file);
        PluginLanguageEditor.Document saved = editor.load("fr_FR").get(5, TimeUnit.SECONDS);

        assertThrows(ExecutionException.class, () -> editor.save(new PluginLanguageEditor.Edit("fr_FR",
                IrisMessages.COMMAND_RELOAD_SUCCESS.id(), saved.snapshot().value(IrisMessages.COMMAND_RELOAD_SUCCESS),
                new TextValue("Missing placeholder"))).get(5, TimeUnit.SECONDS));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    public void incompleteDownloadedLocaleCanBeEditedWithoutBeingSelected() throws Exception {
        Files.writeString(IrisLanguage.remote(directory).cacheFile("fr_FR"), "{\"locale\":\"fr_FR\",\"messages\":{}}");
        PluginLanguageEditor.Document original = editor.load("fr_FR").get(5, TimeUnit.SECONDS);
        TextValue value = new TextValue("Langue {locale}");
        PluginLanguageEditor.Document saved = editor.save(new PluginLanguageEditor.Edit("fr_FR", IrisMessages.COMMAND_RELOAD_SUCCESS.id(),
                original.snapshot().value(IrisMessages.COMMAND_RELOAD_SUCCESS), value)).get(5, TimeUnit.SECONDS);
        assertEquals(value, saved.snapshot().value(IrisMessages.COMMAND_RELOAD_SUCCESS));
        assertEquals("en_US", languages.defaultLocale());
        assertEquals("en_US", IrisLanguage.activeLocale());
    }
}
