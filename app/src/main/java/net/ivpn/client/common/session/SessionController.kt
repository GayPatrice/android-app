package net.ivpn.client.common.session

/*
 IVPN Android app
 https://github.com/ivpn/android-app

 Created by Oleksandr Mykhailenko.
 Copyright (c) 2020 Privatus Limited.

 This file is part of the IVPN Android app.

 The IVPN Android app is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any later version.

 The IVPN Android app is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 details.

 You should have received a copy of the GNU General Public License
 along with the IVPN Android app. If not, see <https://www.gnu.org/licenses/>.
*/

import net.ivpn.client.IVPNApplication
import net.ivpn.client.common.Mapper
import net.ivpn.client.common.prefs.ServersRepository
import net.ivpn.client.common.prefs.Settings
import net.ivpn.client.common.prefs.UserPreference
import net.ivpn.client.rest.HttpClientFactory
import net.ivpn.client.rest.IVPNApi
import net.ivpn.client.rest.RequestListener
import net.ivpn.client.rest.Responses
import net.ivpn.client.rest.data.model.ServiceStatus
import net.ivpn.client.rest.data.model.WireGuard
import net.ivpn.client.rest.data.session.*
import net.ivpn.client.rest.data.wireguard.ErrorResponse
import net.ivpn.client.rest.requests.common.Request
import net.ivpn.client.v2.viewmodel.AccountViewModel
import net.ivpn.client.v2.viewmodel.ViewModelCleaner
import net.ivpn.client.vpn.Protocol
import net.ivpn.client.vpn.ProtocolController
import net.ivpn.client.vpn.controller.VpnBehaviorController
import org.slf4j.LoggerFactory
import javax.inject.Inject

class SessionController @Inject constructor(
        private val userPreference: UserPreference,
        private val settings: Settings,
        private val vpnBehaviorController: VpnBehaviorController,
        private val protocolController: ProtocolController,
        private val clientFactory: HttpClientFactory,
        private val serversRepository: ServersRepository
) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(AccountViewModel::class.java)
    }

    private var deleteSessionRequest: Request<DeleteSessionResponse>? = null
    private var sessionStatusRequest: Request<SessionStatusResponse>? = null
    private var sessionNewRequest: Request<SessionNewResponse>? = null

    private val listeners = arrayListOf<SessionListener>()

    fun subscribe(listener: SessionListener) {
        listeners.add(listener)
    }

    fun unSubscribe(listener: SessionListener) {
        listeners.remove(listener)
    }

    fun createSession(force: Boolean, username: String? = getUsername()) {
        val body = SessionNewRequestBody(username, getWireGuardPublicKey(), force)
        sessionNewRequest = Request(settings, clientFactory, serversRepository, Request.Duration.SHORT)
        LOGGER.info(body.toString())

        sessionNewRequest?.start({ api: IVPNApi -> api.newSession(body) },
                object : RequestListener<SessionNewResponse> {
                    override fun onSuccess(response: SessionNewResponse) {
                        LOGGER.info(response.toString())
                        onCreateSuccess(response)
                    }

                    override fun onError(throwable: Throwable) {
                        LOGGER.error("On create session throwable = $throwable")
                        onCreateError(throwable, null)
                    }

                    override fun onError(error: String) {
                        LOGGER.error("On create session error = $error")
                        val errorResponse = Mapper.errorResponseFrom(error)
                        onCreateError(null, errorResponse)
                    }
                })
    }

    fun updateSessionStatus() {
        val sessionToken = getSessionToken()
        if (sessionToken == null || sessionToken.isEmpty()) {
            return
        }

        val body = SessionStatusRequestBody(getSessionToken())
        LOGGER.info("SessionStatusRequestBody = $body")
        sessionStatusRequest = Request(settings, clientFactory, serversRepository, Request.Duration.SHORT)

        sessionStatusRequest?.start({ api: IVPNApi -> api.sessionStatus(body) },
                object : RequestListener<SessionStatusResponse> {
                    override fun onSuccess(response: SessionStatusResponse) {
                        if (response.status != null && response.status == Responses.SUCCESS) {
                            LOGGER.info("Session status response received successfully")
                            LOGGER.info(response.toString())
                            saveSessionStatus(response.serviceStatus)
                            onUpdateSuccess()
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        LOGGER.error("Failed updating session status throwable = $throwable")
                        onUpdateError(throwable, null)
                    }

                    override fun onError(error: String) {
                        LOGGER.error("Error while getting account status to see the confirmation$error")
                        val errorResponse = Mapper.errorResponseFrom(error)
                        errorResponse?.let {
                            if (it.status == Responses.SERVICE_IS_NOT_ACTIVE){
                                userPreference.putIsActive(false)
                            }
                            if ((it.status == Responses.SESSION_NOT_FOUND)) {
                                clearData()
                                ViewModelCleaner()
                            }
                        }
                        onUpdateError(null, errorResponse)
                    }
                })
    }

    fun logOut() {
        vpnBehaviorController.disconnect()

        val token = userPreference.sessionToken
        val requestBody = DeleteSessionRequestBody(token)
        deleteSessionRequest = Request(settings, clientFactory, serversRepository, Request.Duration.SHORT)

        deleteSessionRequest?.start({ api: IVPNApi -> api.deleteSession(requestBody) },
                object : RequestListener<DeleteSessionResponse?> {
                    override fun onSuccess(response: DeleteSessionResponse?) {
                        LOGGER.info("Deleting session from server state: SUCCESS")
                        LOGGER.info(response.toString())
                        onRemoveSuccess()
                    }

                    override fun onError(throwable: Throwable) {
                        LOGGER.error("Error while deleting session from server", throwable)
                        onRemoveError()
                    }

                    override fun onError(error: String) {
                        LOGGER.error("Error while deleting session from server", error)
                        onRemoveError()
                    }
                })
    }

    fun cancel() {
        deleteSessionRequest?.cancel()
        sessionNewRequest?.cancel()
        sessionStatusRequest?.cancel()
    }

    private fun onRemoveSuccess() {
        clearData()
        for (listener in listeners) {
            listener.onRemoveSuccess()
        }
    }

    private fun onRemoveError() {
        clearData()
        for (listener in listeners) {
            listener.onRemoveError()
        }
    }

    private fun onCreateSuccess(response: SessionNewResponse) {
        if (response.status == null) {
            return
        }

        if (response.status == Responses.SUCCESS) {
            putUserData(response)
            handleWireGuardResponse(response.wireGuard)
        }

        for (listener in listeners) {
            listener.onCreateSuccess(response)
        }
    }

    private fun onCreateError(throwable: Throwable?, errorResponse: ErrorResponse?) {
        for (listener in listeners) {
            listener.onCreateError(throwable, errorResponse)
        }
    }

    private fun onUpdateSuccess() {
        for (listener in listeners) {
            listener.onUpdateSuccess()
        }
    }

    private fun onUpdateError(throwable: Throwable?, errorResponse: ErrorResponse?) {
        for (listener in listeners) {
            listener.onUpdateError(throwable, errorResponse)
        }
    }

    private fun clearData() {
        IVPNApplication.getApplication().appComponent.provideComponentUtil().resetComponents()
        ViewModelCleaner()
    }

    private fun getUsername(): String? {
        return userPreference.userLogin
    }

    private fun getWireGuardPublicKey(): String? {
        return settings.wireGuardPublicKey
    }

    private fun getSessionToken(): String? {
        return userPreference.sessionToken
    }

    private fun putUserData(response: SessionNewResponse) {
        LOGGER.info("Save account data")
        userPreference.putSessionToken(response.token)
        userPreference.putSessionUsername(response.vpnUsername)
        userPreference.putSessionPassword(response.vpnPassword)
        saveSessionStatus(response.serviceStatus)
    }

    private fun saveSessionStatus(serviceStatus: ServiceStatus) {
        if (serviceStatus.isOnFreeTrial == null) {
            return
        }
        userPreference.putIsUserOnTrial(java.lang.Boolean.valueOf(serviceStatus.isOnFreeTrial))
        userPreference.putAvailableUntil(serviceStatus.activeUntil)
        userPreference.putCurrentPlan(serviceStatus.currentPlan)
        userPreference.putPaymentMethod(serviceStatus.paymentMethod)
        userPreference.putIsActive(serviceStatus.isActive)
        if (serviceStatus.capabilities != null) {
            userPreference.putIsUserOnPrivateEmailBeta(serviceStatus.capabilities.contains(Responses.PRIVATE_EMAILS))
            val multiHopCapabilities = serviceStatus.capabilities.contains(Responses.MULTI_HOP)
            userPreference.putCapabilityMultiHop(serviceStatus.capabilities.contains(Responses.MULTI_HOP))
            if (!multiHopCapabilities) {
                settings.enableMultiHop(false)
            }
        }
    }

    private fun handleWireGuardResponse(wireGuard: WireGuard?) {
        LOGGER.info("Handle WireGuard response: $wireGuard")
        if (wireGuard == null || wireGuard.status == null) {
            resetWireGuard()
            return
        }

        if (wireGuard.status == Responses.SUCCESS) {
            putWireGuardData(wireGuard)
        } else {
            LOGGER.error("Error received: ${wireGuard.status} ${wireGuard.message}")
            resetWireGuard()
        }
    }

    private fun putWireGuardData(wireGuard: WireGuard) {
        LOGGER.info("Save WireGuard data")
        settings.wireGuardIpAddress = wireGuard.ipAddress
    }

    private fun resetWireGuard() {
        LOGGER.info("Reset WireGuard protocol")
        protocolController.currentProtocol = Protocol.OPENVPN
    }

    interface SessionListener {
        fun onRemoveSuccess()

        fun onRemoveError()

        fun onCreateSuccess(response: SessionNewResponse)

        fun onCreateError(throwable: Throwable?, errorResponse: ErrorResponse?)

        fun onUpdateSuccess()

        fun onUpdateError(throwable: Throwable?, errorResponse: ErrorResponse?)
    }
}