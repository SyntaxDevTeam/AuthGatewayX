@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.command

import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

interface PasswordCommandGateway {
    fun openOwnPasswordChange(player: Player)
    fun openAdminPasswordChange(player: Player, username: String)
}

fun interface LogoutCommandGateway {
    fun logout(player: Player)
}

class MutableLogoutCommandGateway : LogoutCommandGateway {
    @Volatile var delegate: LogoutCommandGateway? = null
    override fun logout(player: Player) = delegate?.logout(player) ?: Unit
}

class MutablePasswordCommandGateway : PasswordCommandGateway {
    @Volatile var delegate: PasswordCommandGateway? = null

    override fun openOwnPasswordChange(player: Player) = delegate?.openOwnPasswordChange(player) ?: Unit
    override fun openAdminPasswordChange(player: Player, username: String) = delegate?.openAdminPasswordChange(player, username) ?: Unit
}

class PasswordCommandRegistrar(
    private val plugin: JavaPlugin,
    private val gateway: PasswordCommandGateway,
    private val logoutGateway: LogoutCommandGateway,
    private val multiAccountGateway: MultiAccountCommandGateway,
) {
    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                Commands.literal("logout")
                    .requires { it.sender.hasPermission("authgatewayx.command.logout") }
                    .executes { context ->
                        (context.source.sender as? Player)?.let(logoutGateway::logout)
                        1
                    }.build(),
            )
            event.registrar().register(
                Commands.literal("changepassword")
                    .requires { it.sender.hasPermission("authgatewayx.command.changepassword") }
                    .executes { context ->
                        (context.source.sender as? Player)?.let(gateway::openOwnPasswordChange)
                        1
                    }.build(),
            )
            event.registrar().register(
                Commands.literal("authgatewayx")
                    .then(Commands.literal("alts")
                        .requires { it.sender.hasPermission("authgatewayx.admin.alts") }
                        .then(Commands.argument("username", StringArgumentType.word()).executes { context ->
                            multiAccountGateway.show(context.source.sender, StringArgumentType.getString(context, "username"))
                            1
                        }))
                    .then(Commands.literal("setpassword")
                        .requires { it.sender.hasPermission("authgatewayx.admin.password") }
                        .then(Commands.argument("username", StringArgumentType.word()).executes { context ->
                            (context.source.sender as? Player)?.let {
                                gateway.openAdminPasswordChange(it, StringArgumentType.getString(context, "username"))
                            }
                            1
                        }))
                    .build(),
            )
        }
    }
}
