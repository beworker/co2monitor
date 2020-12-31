package de.halfbit.co2monitor.feature.dashboard.backend

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomTypeAdapter
import com.apollographql.apollo.api.CustomTypeValue
import com.apollographql.apollo.api.CustomTypeValue.GraphQLString
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloException
import de.halfbit.co2monitor.feature.dashboard.backend.RecentMeasurements.Data
import de.halfbit.co2monitor.feature.dashboard.backend.model.Measurement
import de.halfbit.co2monitor.main.dashboard.graphql.MeasurementQuery
import de.halfbit.co2monitor.main.dashboard.graphql.type.CustomType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Instant
import magnet.Instance
import okhttp3.OkHttpClient
import java.text.ParseException
import java.util.concurrent.TimeUnit

interface RecentMeasurements {
    fun observe(): Flow<Data>

    sealed class Data {
        data class Success(val measurement: Measurement) : Data()
        sealed class Failure : Data() {
            object EmptyData : Failure()
            object Communication : Failure()
        }
    }
}

@ExperimentalCoroutinesApi
@Instance(type = RecentMeasurements::class)
internal class DefaultRecentMeasurements(
    private val graphqlClient: ApolloClient
) : RecentMeasurements {

    override fun observe(): Flow<Data> =
        channelFlow {
            while (true) {
                val measurement = suspendCancellableCoroutine<Data> { continuation ->
                    graphqlClient.query(MeasurementQuery()).let { call ->
                        call.enqueue(object : ApolloCall.Callback<MeasurementQuery.Data>() {
                            override fun onResponse(response: Response<MeasurementQuery.Data>) {
                                ensureActive()
                                val measurement = response.data?.current?.toMeasurement()
                                val data =
                                    if (measurement == null) Result.success(Data.Failure.EmptyData)
                                    else Result.success(Data.Success(measurement))
                                continuation.resumeWith(data)
                            }

                            override fun onFailure(e: ApolloException) {
                                ensureActive()
                                e.printStackTrace()
                                continuation.resumeWith(
                                    Result.success(Data.Failure.Communication)
                                )
                            }
                        })
                        continuation.invokeOnCancellation { call.cancel() }
                    }
                }
                channel.send(measurement)
                delay(5 * 1000)
            }
        }
}

private fun MeasurementQuery.Current.toMeasurement(): Measurement =
    Measurement(
        time = time,
        co2 = co2,
        temperature = temperature
    )

@Instance(type = OkHttpClient::class)
internal fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

@Instance(type = ApolloClient::class)
internal fun provideApolloClient(httpClient: OkHttpClient): ApolloClient =
    ApolloClient
        .builder()
        .addCustomTypeAdapter(CustomType.DATETIME, InstantTypeAdapter())
        .serverUrl("http://192.168.178.28:8080/graphql")
        .callFactory { request -> httpClient.newCall(request) }
        .build()

private class InstantTypeAdapter : CustomTypeAdapter<Instant> {
    override fun decode(value: CustomTypeValue<*>): Instant {
        return try {
            Instant.parse(value.value.toString())
        } catch (e: ParseException) {
            throw RuntimeException(e)
        }
    }

    override fun encode(value: Instant): CustomTypeValue<*> {
        return GraphQLString(value.toString())
    }
}