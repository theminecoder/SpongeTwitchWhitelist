package me.theminecoder.spongetwitchwhitelist;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "spongetwitchwhitelist",
        name = "SpongeTwitchWhitelist",
        authors = {
                "theminecoder"
        }
)
public class SpongeTwitchWhitelist {

    @Inject
    private Logger logger;

    @Inject
    @DefaultConfig(sharedRoot = true)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private List<String> serviceIds;
    private Task task;

    private Cache<String, Boolean> whitelistStatus = CacheBuilder.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    @Listener
    public void onServerStart(GameStartedServerEvent event) {
        try {
            configManager.getDefaultOptions().setShouldCopyDefaults(true);
            CommentedConfigurationNode config = configManager.load();
            serviceIds = config.getNode("whitelistIds").getList(TypeToken.of(String.class));
            config.getNode("whitelistIds").setValue(serviceIds);
            configManager.save(config);
        } catch (Exception e) {
            logger.error("Unable to load config", e);
        }

        if (serviceIds.size() > 0) {
            task = Task.builder().async().interval(5, TimeUnit.MINUTES).execute(this::refreshWhitelist)
                    .name("Twitch Whitelist Refresh - Auto (5 Mins)").submit(this);
        } else {
            logger.error("No whitelist service ids configured! Please check the config!");
        }

        Sponge.getCommandManager().register(this, CommandSpec.builder()
                        .description(Text.of("Reloads the twitch whitelist"))
                        .permission("twitchwhitelist.reload")
                        .executor((src, args) -> {
                            Task.builder().async().execute(() -> {
                                this.refreshWhitelist();
                                src.sendMessage(Text.builder("Twitch whitelist refreshed!").color(TextColors.GREEN).build());
                            }).name("Twitch Whitelist Refresh - Manual (" + src.getName() + ")").submit(this);
                            return CommandResult.success();
                        }).build(),
                "twitchrefresh"
        );
    }

    @Listener
    public void onClientConnectionLogin(ClientConnectionEvent.Login event) {
        boolean whitelisted = whitelistStatus.getIfPresent(event.getTargetUser().getName().toLowerCase()) != null;
        if (!whitelisted && !event.getTargetUser().hasPermission("twitchwhitelist.bypass")) {
            event.setCancelled(true);
            event.setMessage(Text.builder("You are not on the Twitch subserver whitelist!\n\n" +
                    "Head to http://whitelist.twitchapps.com to activate your whitelist.").color(TextColors.RED).build());
        }
    }

    private void refreshWhitelist() {
        serviceIds.stream().map(this::getWhitelistUsers).flatMap(Collection::stream).map(String::toLowerCase).forEach(
                username -> whitelistStatus.put(username, true));
    }

    private List<String> getWhitelistUsers(String id) {
        List<String> usernames = Lists.newArrayList();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new URL("http://whitelist.twitchapps.com/list.php?id=" + URLEncoder.encode(id, "UTF-8")).openStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                usernames.add(line);
            }
        } catch (Exception e) {
            logger.warn("Error attempting to retrieve usernames for whitelist ID \"" + id + "\"", e);
        } finally {
            logger.info("Loaded " + usernames.size() + " username" + (usernames.size() == 1 ? "" : "s") + " from the whitelist server for ID \"" + id + "\"");
        }
        return usernames;
    }

}
