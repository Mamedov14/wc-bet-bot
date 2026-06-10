package wcbet

import ru.tinkoff.kora.application.graph.KoraApplication
import ru.tinkoff.kora.common.KoraApp
import ru.tinkoff.kora.config.hocon.HoconConfigModule
import ru.tinkoff.kora.database.flyway.FlywayJdbcDatabaseModule
import ru.tinkoff.kora.database.jdbc.JdbcDatabaseModule
import ru.tinkoff.kora.http.client.ok.OkHttpClientModule
import ru.tinkoff.kora.json.module.JsonModule
import ru.tinkoff.kora.logging.logback.LogbackModule
import ru.tinkoff.kora.scheduling.jdk.SchedulingJdkModule

@KoraApp
interface Application :
    HoconConfigModule,
    LogbackModule,
    JsonModule,
    OkHttpClientModule,
    JdbcDatabaseModule,
    FlywayJdbcDatabaseModule,
    SchedulingJdkModule

fun main() {
    KoraApplication.run { ApplicationGraph.graph() }
}
