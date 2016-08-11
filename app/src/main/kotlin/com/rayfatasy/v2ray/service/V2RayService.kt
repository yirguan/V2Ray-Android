package com.rayfatasy.v2ray.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.StrictMode
import android.preference.PreferenceManager
import com.eightbitlab.rxbus.Bus
import com.eightbitlab.rxbus.registerInBus
import com.google.gson.Gson
import com.orhanobut.logger.Logger
import com.rayfatasy.v2ray.R
import com.rayfatasy.v2ray.dto.V2RayConfigOutbound
import com.rayfatasy.v2ray.event.*
import com.rayfatasy.v2ray.getV2RayApplication
import com.rayfatasy.v2ray.ui.MainActivity
import com.rayfatasy.v2ray.util.AssetsUtil
import go.libv2ray.Libv2ray
import org.jetbrains.anko.notificationManager
import org.jetbrains.anko.startService
import java.io.File

class V2RayService : Service() {
    companion object {
        const val NOTIFICATION_ID = 0
        const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
        const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 0
        const val ACTION_STOP_V2RAY = "com.rayfatasy.v2ray.action.STOP_V2RAY"

        fun startV2Ray(context: Context) {
            val config = generateV2RayConfigOutbound(context)
            val templateStr = AssetsUtil.readTextFromAssets(context.assets, "conf_template.txt")
            val gson = Gson()
            val ret = templateStr.replace("<outbound>", gson.toJson(config))
            File(context.getV2RayApplication().configFilePath).writeText(ret)
            context.startService<V2RayService>()
        }

        fun stopV2Ray() {
            Bus.send(StopV2RayEvent)
        }

        fun checkStatusEvent(callback: (Boolean) -> Unit) {
            Bus.send(CheckV2RayStatusEvent(callback))
        }

        fun generateV2RayConfigOutbound(ctx: Context): V2RayConfigOutbound {
            val config = V2RayConfigOutbound()
            val preference = PreferenceManager.getDefaultSharedPreferences(ctx)
            val vnextBean = config.settings.vnext[0]
            vnextBean.address = preference.getString(MainActivity.MainFragment.PREF_SERVER_ADDRESS,
                    vnextBean.address)
            vnextBean.port = preference.getString(MainActivity.MainFragment.PREF_SERVER_PORT,
                    vnextBean.port.toString()).toInt()
            val usersBean = vnextBean.users[0]
            usersBean.id = preference.getString(MainActivity.MainFragment.PREF_USER_ID,
                    usersBean.id)
            usersBean.alterId = preference.getString(MainActivity.MainFragment.PREF_USER_ALTER_ID,
                    usersBean.alterId.toString()).toInt()
            usersBean.email = preference.getString(MainActivity.MainFragment.PREF_USER_EMAIL,
                    usersBean.email)
            config.streamSettings.network = preference.getString(MainActivity.MainFragment.PREF_STREAM_NETWORK,
                    config.streamSettings.network)
            return config
        }
    }

    private val v2rayPoint = Libv2ray.NewV2RayPoint()
    private var vpnService: V2RayVpnService? = null
    private val v2rayCallback = V2RayCallback()
    private val stopV2RayReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            stopV2Ray()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        v2rayPoint.packageName = packageName

        Bus.observe<VpnServiceSendSelfEvent>()
                .subscribe {
                    vpnService = it.vpnService
                    vpnCheckIsReady()
                }
                .registerInBus(this)

        Bus.observe<StopV2RayEvent>()
                .subscribe {
                    stopV2Ray()
                }
                .registerInBus(this)

        Bus.observe<VpnServiceStatusEvent>()
                .filter { !it.isRunning }
                .subscribe { stopV2Ray() }
                .registerInBus(this)

        Bus.observe<CheckV2RayStatusEvent>()
                .subscribe {
                    val prepare = VpnService.prepare(this)
                    val isRunning = prepare == null && vpnService != null && v2rayPoint.isRunning
                    it.callback(isRunning)
                }

        registerReceiver(stopV2RayReceiver, IntentFilter(ACTION_STOP_V2RAY))
    }

    override fun onDestroy() {
        super.onDestroy()
        Bus.unregister(this)
        unregisterReceiver(stopV2RayReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startV2ray()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun vpnPrepare(): Int {
        startService<V2RayVpnService>()
        return 1
    }

    private fun vpnCheckIsReady() {
        val prepare = VpnService.prepare(this)

        if (prepare != null) {
            Bus.send(VpnPrepareEvent(prepare) {
                if (it)
                    vpnCheckIsReady()
                else
                    v2rayPoint.StopLoop()
            })
            return
        }

        if (this.vpnService != null) {
            v2rayPoint.VpnSupportReady()
            Bus.send(V2RayStatusEvent(true))
            showNotification()
        }
    }

    private fun startV2ray() {
        Logger.d(v2rayPoint)
        if (!v2rayPoint.isRunning) {
            v2rayPoint.callbacks = v2rayCallback
            v2rayPoint.vpnSupportSet = v2rayCallback
            v2rayPoint.configureFile = getV2RayApplication().configFilePath
            v2rayPoint.RunLoop()
        }
    }

    private fun stopV2Ray() {
        if (v2rayPoint.isRunning) {
            v2rayPoint.StopLoop()
        }
        vpnService = null
        Bus.send(V2RayStatusEvent(false))
        cancelNotification()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun showNotification() {
        val startMainIntent = Intent(applicationContext, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(applicationContext,
                NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationBuilder = Notification.Builder(applicationContext)
                .setSmallIcon(R.drawable.ic_action_logo)
                .setContentTitle(getString(R.string.notification_content_title))
                .setContentText(getString(R.string.notification_content_text))
                .setContentIntent(contentPendingIntent)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val stopV2RayIntent = Intent(ACTION_STOP_V2RAY)
            val stopV2RayPendingIntent = PendingIntent.getBroadcast(applicationContext,
                    NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            notificationBuilder
                    .addAction(R.drawable.ic_close_blue_grey_800_18dp,
                            getString(R.string.notification_action_stop_v2ray),
                            stopV2RayPendingIntent)
                    .build()
        } else {
            notificationBuilder.notification
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private inner class V2RayCallback : Libv2ray.V2RayCallbacks, Libv2ray.V2RayVPNServiceSupportsSet {
        override fun Shutdown() = 0L

        override fun GetVPNFd() = vpnService!!.getFd().toLong()

        override fun Prepare() = vpnPrepare().toLong()

        override fun Protect(l: Long) = (if (vpnService!!.protect(l.toInt())) 0 else 1).toLong()

        override fun OnEmitStatus(l: Long, s: String?): Long {
            Logger.d(s)
            return 0
        }

        override fun Setup(s: String): Long {
            Logger.d(s)
            try {
                vpnService!!.setup(s)
                return 0
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            }
        }
    }
}
