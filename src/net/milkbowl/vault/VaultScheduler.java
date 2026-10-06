/* This file is part of Vault.

    Vault is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Vault is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with Vault.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.milkbowl.vault;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Schedules tasks on both classic Bukkit/Spigot/Paper servers and on
 * region-threaded servers (Folia and its forks such as Canvas), where the
 * legacy BukkitScheduler is unsupported. The region-threaded schedulers are
 * accessed reflectively so Vault keeps compiling against the Bukkit API.
 */
final class VaultScheduler {

    private static final String GLOBAL_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";
    private static final String ASYNC_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.AsyncScheduler";
    private static final boolean REGIONIZED = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final Plugin plugin;

    VaultScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs a task on the next tick of the main thread (or the global region thread).
     */
    void runGlobal(final Runnable task) {
        if (!REGIONIZED) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = getScheduler("getGlobalRegionScheduler");
            Method run = Class.forName(GLOBAL_SCHEDULER).getMethod("run", Plugin.class, Consumer.class);
            run.invoke(scheduler, plugin, (Consumer<Object>) scheduledTask -> task.run());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to schedule global region task", e);
        }
    }

    /**
     * Runs a repeating task off the main thread.
     * @param delayTicks initial delay in server ticks
     * @param periodTicks period in server ticks
     */
    void runAsyncTimer(final Runnable task, long delayTicks, long periodTicks) {
        if (!REGIONIZED) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return;
        }
        try {
            Object scheduler = getScheduler("getAsyncScheduler");
            Method runAtFixedRate = Class.forName(ASYNC_SCHEDULER).getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
            // Folia's async scheduler works in real time rather than ticks (1 tick = 50ms)
            runAtFixedRate.invoke(scheduler, plugin, (Consumer<Object>) scheduledTask -> task.run(),
                    Math.max(1, delayTicks * 50), Math.max(1, periodTicks * 50), TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to schedule async task", e);
        }
    }

    /**
     * Cancels every task this plugin has scheduled.
     */
    void cancelAll() {
        if (!REGIONIZED) {
            Bukkit.getScheduler().cancelTasks(plugin);
            return;
        }
        cancelTasks("getGlobalRegionScheduler", GLOBAL_SCHEDULER);
        cancelTasks("getAsyncScheduler", ASYNC_SCHEDULER);
    }

    private void cancelTasks(String schedulerGetter, String schedulerClass) {
        try {
            Object scheduler = getScheduler(schedulerGetter);
            Class.forName(schedulerClass).getMethod("cancelTasks", Plugin.class).invoke(scheduler, plugin);
        } catch (ReflectiveOperationException e) {
            // Nothing to cancel if the scheduler is unavailable
        }
    }

    private static Object getScheduler(String getter) throws ReflectiveOperationException {
        return Server.class.getMethod(getter).invoke(Bukkit.getServer());
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
