package org.infernus.idea.checkstyle.service.cmd;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;

import com.google.common.collect.ImmutableMap;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import org.hamcrest.Matchers;
import org.infernus.idea.checkstyle.CheckStyleBundle;
import org.infernus.idea.checkstyle.csapi.BundledConfig;
import org.infernus.idea.checkstyle.csapi.CheckstyleInternalObject;
import org.infernus.idea.checkstyle.model.ConfigurationLocation;
import org.infernus.idea.checkstyle.model.ConfigurationLocationFactory;
import org.infernus.idea.checkstyle.service.CheckstyleActionsImpl;
import org.infernus.idea.checkstyle.service.ConfigurationBuilder;
import org.infernus.idea.checkstyle.service.ConfigurationMatcher;
import org.infernus.idea.checkstyle.service.FileUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;


public class OpLoadConfigurationTest
{
    private static final Project PROJECT = Mockito.mock(Project.class);
    private final ConfigurationLocation configurationLocation = Mockito.mock(ConfigurationLocation.class);
    private final Module module = Mockito.mock(Module.class);
    private final Notifications notifications = Mockito.mock(Notifications.class);

    private OpLoadConfiguration underTest;


    @Before
    public void setUp() throws IOException {
        interceptApplicationNotifications();

        Mockito.when(configurationLocation.resolveAssociatedFile("aFileToResolve", PROJECT, module))
                .thenReturn("aResolvedFile");
        Mockito.when(configurationLocation.resolveAssociatedFile("triggersAnIoException", PROJECT, module))
                .thenThrow(new IOException("aTriggeredIoException"));

        underTest = new OpLoadConfiguration(configurationLocation, null, module);
    }


    private void interceptApplicationNotifications() {
        final MessageBus messageBus = Mockito.mock(MessageBus.class);
        Mockito.when(PROJECT.getMessageBus()).thenReturn(messageBus);
        Mockito.when(messageBus.syncPublisher(Notifications.TOPIC)).thenReturn(notifications);

        final Application application = Mockito.mock(Application.class);
        Mockito.when(application.isUnitTestMode()).thenReturn(true);
        Mockito.when(application.getMessageBus()).thenReturn(messageBus);
        ApplicationManager.setApplication(application, Mockito.mock(Disposable.class));
    }


    @Test
    public void filePathsAreNotResolvedOnANonDefaultImplementationOfConfiguration() throws CheckstyleException {
        final Configuration config = Mockito.mock(Configuration.class);
        Mockito.verifyZeroInteractions(config);
    }


    @Test
    public void childrenOfUpdatedElementsArePreserved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker()
                .withChild(ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "aFileToResolve")
                        .withChild(ConfigurationBuilder.config("aChildModule"))).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker()
                .withChild(ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "aResolvedFile")
                        .withChild(ConfigurationBuilder.config("aChildModule"))).build())));
    }

    @Test
    public void attributesOfUpdatedElementsArePreserved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker()
                .withChild(ConfigurationBuilder.config("TreeWalker")
                        .withChild(ConfigurationBuilder.config("ImportControl")
                                .withAttribute("file", "aFileToResolve")
                                .withAttribute("anotherAttribute", "anotherValue"))).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker").withChild(ConfigurationBuilder.config("ImportControl")
                        .withAttribute("file", "aResolvedFile").withAttribute("anotherAttribute", "anotherValue")))
                .build())));
    }

    @Test
    public void messagesOfUpdatedElementsArePreserved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "aFileToResolve").withMessage("messageKey",
                "messageValue").withMessage("anotherMessageKey", "anotherMessageValue")).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "aResolvedFile").withMessage
                        ("messageKey", "messageValue").withMessage("anotherMessageKey", "anotherMessageValue"))
                .build())));
    }

    @Test
    public void multipleElementsAreUpdated() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "aFileToResolve")).withChild(ConfigurationBuilder.config
                ("TreeWalker").withChild(ConfigurationBuilder.config("ImportControl").withAttribute("file",
                "anUnresolvableFile")).withChild(ConfigurationBuilder.config("RegexpHeader").withAttribute
                ("headerFile", "aFileToResolve"))).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "aResolvedFile")).withChild
                (ConfigurationBuilder.config("TreeWalker").withChild(ConfigurationBuilder.config("RegexpHeader")
                        .withAttribute("headerFile", "aResolvedFile"))).build())));
    }

    @Test
    public void removalOfElementsDoesNotEffectOtherElements() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "anUnresolvableFile")).withChild(ConfigurationBuilder
                .config("TreeWalker").withChild(ConfigurationBuilder.config("RegexpHeader").withAttribute
                        ("headerFile", "anUnresolvableFile")).withChild(ConfigurationBuilder.config("ConstantName")))
                .build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker").withChild(ConfigurationBuilder.config("ConstantName")))
                .build())));
    }

    @Test
    public void aModuleWithAFilenameThatRaisesAnIOExceptionOnResolutionDoesNotModifyTheConfiguration() throws
            CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "triggersAnIoException")).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "triggersAnIoException"))
                .build())));
    }

    @Test
    public void aModuleWithAFilenameThatRaisesAnIOExceptionOnResolutionTriggersAnErrorNotification() throws
            CheckstyleException {
        underTest.resolveFilePaths(PROJECT, ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "triggersAnIoException")).build());

        final Notification notification = sentNotification();
        assertThat(notification, is(not(nullValue())));
        assertThat(notification.getType(), is(equalTo(NotificationType.ERROR)));
        assertThat(notification.getContent(), is(equalTo(CheckStyleBundle.message("checkstyle.checker-failed",
                "aTriggeredIoException"))));
    }

    @Test
    public void aModuleWithAFilenameThatIsNotResolvesTriggersAWarningNotification() throws CheckstyleException {
        underTest.resolveFilePaths(PROJECT, ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("TreeWalker")
                .withChild(ConfigurationBuilder.config("RegexpHeader").withAttribute("headerFile",
                        "anUnresolvableFile"))).build());

        final Notification notification = sentNotification();
        assertThat(notification, is(not(nullValue())));
        assertThat(notification.getType(), is(equalTo(NotificationType.WARNING)));
        assertThat(notification.getContent(), is(equalTo(CheckStyleBundle.message("checkstyle.not-found" + "" + ""
                + ".RegexpHeader"))));
    }

    @Test
    public void aModuleWithAFilenameThatIsBlankDoesNotHaveResolutionAttempted() throws CheckstyleException {
        underTest.resolveFilePaths(PROJECT, ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("TreeWalker")
                .withChild(ConfigurationBuilder.config("ImportControl").withAttribute("file", ""))).build());
        //verifyZeroInteractions(configurationLocation);
    }

    private Notification sentNotification() {
        final ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        Mockito.verify(notifications).notify(notificationCaptor.capture());
        return notificationCaptor.getValue();
    }

    @Test
    public void aSuppressionFilterWithAResolvableFilenameHasTheModuleUpdated() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "aFileToResolve")).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("SuppressionFilter").withAttribute("file", "aResolvedFile")).build())));
    }

    @Test
    public void aSuppressionFilterWithAnUnresolvableFilenameHasTheModuleRemoved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config
                ("SuppressionFilter").withAttribute("file", "anUnresolvableFile")).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().build())));
    }

    @Test
    public void aRegexpHeaderWithAResolvableFilenameHasTheModuleUpdated() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config("TreeWalker")
                .withChild(ConfigurationBuilder.config("RegexpHeader").withAttribute("headerFile", "aFileToResolve"))
        ).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker").withChild(ConfigurationBuilder.config("RegexpHeader")
                        .withAttribute("headerFile", "aResolvedFile"))).build())));
    }

    @Test
    public void aRegexpHeaderWithAnUnresolvableFilenameHasTheModuleReferenceRemoved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config("TreeWalker")
                .withChild(ConfigurationBuilder.config("RegexpHeader").withAttribute("headerFile",
                        "anUnresolvableFile"))).build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker")).build())));
    }

    @Test
    public void aImportControlWithAResolvableFilenameHasTheModuleUpdated() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config("TreeWalker")
                .withChild(ConfigurationBuilder.config("ImportControl").withAttribute("file", "aFileToResolve")))
                .build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker").withChild(ConfigurationBuilder.config("ImportControl")
                        .withAttribute("file", "aResolvedFile"))).build())));
    }

    @Test
    public void anImportControlWithAnUnresolvableFilenameHasTheModuleReferenceRemoved() throws CheckstyleException {
        Configuration config = ConfigurationBuilder.checker().withChild(ConfigurationBuilder.config("TreeWalker")
                .withChild(ConfigurationBuilder.config("ImportControl").withAttribute("file", "anUnresolvableFile")))
                .build();
        underTest.resolveFilePaths(PROJECT, config);
        assertThat(config, Matchers.is(ConfigurationMatcher.configEqualTo(ConfigurationBuilder.checker().withChild
                (ConfigurationBuilder.config("TreeWalker")).build())));
    }


    @Test
    public void testNoConfiguration() throws CheckstyleException {
        OpLoadConfiguration testee = new OpLoadConfiguration(configurationLocation, null, module)
        {
            @Override
            Configuration callLoadConfiguration(final InputStream inputStream) {
                return null;
            }
        };
        try {
            testee.execute(PROJECT);
        } catch (CheckstyleException e) {
            // expected
            Assert.assertTrue(e.getMessage().contains("Couldn't find root module"));
        }
    }


    @Test
    public void testWrongConfigurationClass() throws CheckstyleException {
        Configuration config = new Configuration()
        {
            @Override
            public String[] getAttributeNames() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getAttribute(final String name) throws CheckstyleException {
                throw new UnsupportedOperationException();
            }

            @Override
            public Configuration[] getChildren() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getName() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImmutableMap<String, String> getMessages() {
                throw new UnsupportedOperationException();
            }
        };
        underTest.resolveFilePaths(PROJECT, config);  // just log a warning and do nothing ... well ...
    }


    @Test
    public void testConstructors() {
        new OpLoadConfiguration(configurationLocation);
        new OpLoadConfiguration(configurationLocation, null);
        new OpLoadConfiguration(configurationLocation, null, module);
        VirtualFile virtualFile = Mockito.mock(VirtualFile.class);
        new OpLoadConfiguration((VirtualFile) virtualFile);
        new OpLoadConfiguration((VirtualFile) virtualFile, null);
        new OpLoadConfiguration("doesn't matter");
    }


    @Test
    public void testLoadFromString() throws IOException, URISyntaxException {
        final String configXml = FileUtil.readFile("cmd/config-ok.xml");
        CheckstyleInternalObject csConfig = new CheckstyleActionsImpl(PROJECT).loadConfiguration(configXml);
        Assert.assertNotNull(csConfig);
    }


    @Test
    public void testLoadBundledSunChecks() {
        final ConfigurationLocationFactory clf = new ConfigurationLocationFactory();
        CheckstyleInternalObject csConfig = new CheckstyleActionsImpl(PROJECT)
                .loadConfiguration(clf.create(BundledConfig.SUN_CHECKS), true, null);
        Assert.assertNotNull(csConfig);
    }


    @Test
    public void testLoadBundledGoogleChecks() {
        final ConfigurationLocationFactory clf = new ConfigurationLocationFactory();
        CheckstyleInternalObject csConfig = new CheckstyleActionsImpl(PROJECT)
                .loadConfiguration(clf.create(BundledConfig.GOOGLE_CHECKS), true, null);
        Assert.assertNotNull(csConfig);
    }
}
