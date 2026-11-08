package pl.syntaxdevteam.authgatewayx.paper.integration

import org.bukkit.Server
import pl.syntaxdevteam.authgatewayx.integrations.*
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class PaperIntegrationResolver(
    private val server: Server,
    private val executor: BoundedTaskExecutor,
) {
    fun usernamePolicy(): UsernamePolicyProvider? {
        server.servicesManager.load(UsernamePolicyProvider::class.java)?.let { return it }
        val api = loadService("CleanerX", "pl.syntaxdevteam.cleanerx.api.CleanerXAPI") ?: return null
        val contains = api.javaClass.methods.firstOrNull {
            it.name == "containsBannedWord" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: return null
        return UsernamePolicyProvider { username ->
            executor.submit {
                if (contains.invoke(api, username.value) == true) UsernameVerdict.DENY_PROFANITY else UsernameVerdict.ALLOW
            }
        }
    }

    fun punishmentProvider(): PunishmentProvider? {
        server.servicesManager.load(PunishmentProvider::class.java)?.let { return it }
        val api = loadService("PunisherX", "pl.syntaxdevteam.punisher.api.PunisherXApi") ?: return null
        val getActive = api.javaClass.methods.firstOrNull {
            it.name == "getActivePunishments" && it.parameterCount == 2
        } ?: return null
        return PunishmentProvider { identity ->
            val uuid = identity.minecraftUuid
                ?: return@PunishmentProvider CompletableFuture.completedFuture(LoginPunishmentResult.Allow)
            @Suppress("UNCHECKED_CAST")
            val stage = getActive.invoke(api, uuid.toString(), "ALL") as? CompletionStage<List<Any>>
                ?: return@PunishmentProvider CompletableFuture.completedFuture(LoginPunishmentResult.Unavailable())
            stage.handle { punishments, failure ->
                if (failure != null) LoginPunishmentResult.Unavailable(failure)
                else punishments.orEmpty().firstOrNull(::isLoginBan)?.let { punishment ->
                    LoginPunishmentResult.Deny(
                        reasonCode = "PUNISHERX_ACTIVE_BAN",
                        punishmentId = readProperty(punishment, "getId")?.toString(),
                    )
                } ?: LoginPunishmentResult.Allow
            }
        }
    }

    private fun loadService(pluginName: String, className: String): Any? {
        val plugin = server.pluginManager.getPlugin(pluginName) ?: return null
        val apiClass = runCatching { plugin.javaClass.classLoader.loadClass(className) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return server.servicesManager.load(apiClass as Class<Any>)
    }

    private fun isLoginBan(punishment: Any): Boolean {
        val type = readProperty(punishment, "getType")?.toString()?.uppercase() ?: return false
        return type == "BAN" || type == "NETWORK_BAN" || type == "IP_BAN"
    }

    private fun readProperty(instance: Any, getter: String): Any? =
        runCatching { instance.javaClass.getMethod(getter).invoke(instance) }.getOrNull()
}
