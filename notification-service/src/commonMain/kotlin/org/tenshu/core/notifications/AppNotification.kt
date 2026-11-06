package org.tenshu.core.notifications

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

interface AppNotification : Notification {

    override val schema: CompositeSchema get() = Schema

    val appName: TString
        get() = getField(Fields.APP_NAME)

    companion object {
        const val ID = "tenshu/notifications/app"

        val Schema = composite(
            id = ID,
            parent = Notification.Schema,
            fields = {
                string(Fields.APP_NAME)
            }
        )
    }

    object Fields {
        const val APP_NAME = "$ID/appName"
    }
}

val Entity<out AppNotification>.appName: TString
    get() = getFieldValue(AppNotification.Fields.APP_NAME)

interface AppRegisteredNotification : AppNotification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/notifications/app/registered"

        val Schema = composite(
            id = ID,
            parent = AppNotification.Schema
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            appName: String
        ): AppRegisteredNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, appName)
        )
    }
}

interface AppRemovedNotification : AppNotification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/notifications/app/removed"

        val Schema = composite(
            id = ID,
            parent = AppNotification.Schema
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            appName: String
        ): AppRemovedNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, appName)
        )
    }
}
