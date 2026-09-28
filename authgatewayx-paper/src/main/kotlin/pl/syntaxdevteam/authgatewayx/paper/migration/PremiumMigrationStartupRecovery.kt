package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationCoordinator
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRunResult
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class PremiumMigrationStartupRecovery(
    private val storage: AccountStorage,
    private val coordinator: PremiumMigrationCoordinator,
    private val scheduler: PaperPlatformScheduler,
    private val isTargetOnline: (UUID) -> Boolean,
    private val logger: Logger,
    private val batchLimit: Int = 100,
) {
    init {
        require(batchLimit in 1..1000)
    }

    fun schedule() {
        scheduler.delayedGlobal(20L, Runnable { scan() })
    }

    private fun scan() {
        storage.findIncompletePremiumMigrations(batchLimit).whenComplete { tickets, failure ->
            if (failure != null) {
                logger.log(Level.SEVERE, "Cannot inspect unfinished premium migrations during startup", failure)
                return@whenComplete
            }
            scheduler.global(Runnable {
                tickets.orEmpty().forEach { ticket ->
                    if (isTargetOnline(ticket.targetMinecraftUuid)) {
                        logger.warning(
                            "Skipping unfinished premium migration ${ticket.id} for ${ticket.username.value}: target UUID is online",
                        )
                    } else {
                        coordinator.migrate(ticket).whenComplete { result, migrationFailure ->
                            report(ticket, result, migrationFailure)
                        }
                    }
                }
            })
        }
    }

    private fun report(
        ticket: PremiumMigrationTicket,
        result: PremiumMigrationRunResult?,
        failure: Throwable?,
    ) {
        when {
            failure != null -> logger.log(
                Level.SEVERE,
                "Startup recovery failed for premium migration ${ticket.id} (${ticket.username.value})",
                failure,
            )
            result is PremiumMigrationRunResult.Completed -> logger.info(
                "Startup recovery completed premium migration ${ticket.id} for ${ticket.username.value}",
            )
            result is PremiumMigrationRunResult.Blocked -> logger.warning(
                "Startup recovery blocked premium migration ${ticket.id} for ${ticket.username.value} " +
                    "by ${result.providerId}: ${result.reasonCode}",
            )
            result is PremiumMigrationRunResult.Failed -> logger.warning(
                "Startup recovery failed premium migration ${ticket.id} for ${ticket.username.value}: ${result.reasonCode}",
            )
            result is PremiumMigrationRunResult.AlreadyRunning -> logger.info(
                "Premium migration ${ticket.id} for ${ticket.username.value} is already running",
            )
        }
    }
}
