package au.com.dius.pact.provider.maven

import au.com.dius.pact.model.Interaction
import au.com.dius.pact.model.Pact
import au.com.dius.pact.model.Pact$
import au.com.dius.pact.provider.groovysupport.ProviderClient
import au.com.dius.pact.provider.groovysupport.ResponseComparison
import groovyx.net.http.RESTClient
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.json4s.FileInput
import org.json4s.StreamInput
import scala.collection.JavaConverters$

@Mojo(name = 'verify')
class PactProviderMojo extends AbstractMojo {

    @Parameter
    private List<Provider> serviceProviders

    @Parameter
    private Map<String, String> configuration = [:]

    @Override
    void execute() throws MojoExecutionException, MojoFailureException {
        Map failures = [:]
        serviceProviders.each { provider ->
            provider.consumers.findAll(this.&filterConsumers).each { consumer ->
                AnsiConsole.out().println(Ansi.ansi().a('\nVerifying a pact between ').bold().a(consumer.name)
                    .boldOff().a(' and ').bold().a(provider.name).boldOff())

                Pact pact
                if (consumer.pactFile) {
                    AnsiConsole.out().println(Ansi.ansi().a("  [Using file ${consumer.pactFile}]"))
                    pact = Pact$.MODULE$.from(new FileInput(consumer.pactFile))
                } else if (consumer.pactUrl) {
                    AnsiConsole.out().println(Ansi.ansi().a("  [from URL ${consumer.pactUrl}]"))
                    pact = Pact$.MODULE$.from(new StreamInput(consumer.pactUrl.newInputStream()))
                } else {
                    throw new RuntimeException("You must specify the pactfile to execute for consumer '${consumer.name}' (use <pactFile> or <pactUrl>)")
                }

                def interactions = JavaConverters$.MODULE$.seqAsJavaListConverter(pact.interactions())
                interactions.asJava().findAll(this.&filterInteractions).each { Interaction interaction ->
                    def interactionMessage = "Verifying a pact between ${consumer.name} and ${provider.name} - ${interaction.description()}"

                    def stateChangeOk = true
                    if (interaction.providerState.defined) {
                        stateChangeOk = stateChange(interaction.providerState.get(), consumer)
                        if (stateChangeOk != true) {
                            failures[interactionMessage] = stateChangeOk
                            stateChangeOk = false
                        } else {
                            interactionMessage += " Given ${interaction.providerState.get()}"
                        }
                    }

                    if (stateChangeOk) {
                        AnsiConsole.out().println(Ansi.ansi().a('  ').a(interaction.description()))

                        try {
                            ProviderClient client = new ProviderClient(request: interaction.request(), provider: provider)

                            def expectedResponse = interaction.response()
                            def actualResponse = client.makeRequest()

                            def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
                                actualResponse.statusCode, actualResponse.headers, actualResponse.data)

                            AnsiConsole.out().println('    returns a response which')
                            displayMethodResult(failures, expectedResponse.status(), comparison.method,
                                interactionMessage + ' returns a response which')
                            displayHeadersResult(failures, expectedResponse.headers(), comparison.headers,
                                interactionMessage + ' returns a response which')
                            def expectedBody = expectedResponse.body().defined ? expectedResponse.body().get() : ''
                            displayBodyResult(failures, expectedBody, comparison.body,
                                interactionMessage + ' returns a response which')
                        } catch (e) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a('Request Failed - ')
                                .a(e.message).reset())
                            failures[interactionMessage] = e
                            if (propertyDefined('pact.showStacktrace')) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }

        if (failures.size() > 0) {
            AnsiConsole.out().println('\nFailures:\n')
            failures.eachWithIndex { err, i ->
                AnsiConsole.out().println("$i) ${err.key}")
                if (err.value instanceof Exception || err.value instanceof Error) {
                    err.value.message.split('\n').each {
                        AnsiConsole.out().println("      $it")
                    }
                } else if (err.value instanceof Map && err.value.containsKey('diff')) {
                    err.value.comparison.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }

                    AnsiConsole.out().println()
                    AnsiConsole.out().println("      Diff:")
                    AnsiConsole.out().println()

                    err.value.diff.each { delta ->
                        if (delta.startsWith('@')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.CYAN).a(delta).reset())
                        } else if (delta.startsWith('-')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a(delta).reset())
                        } else if (delta.startsWith('+')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.GREEN).a(delta).reset())
                        } else {
                            AnsiConsole.out().println("      $delta")
                        }
                    }
                } else {
                    err.value.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }
                }
                AnsiConsole.out().println()
            }

            throw new RuntimeException("There were ${failures.size()} pact failures")
        }
    }

    void displayMethodResult(Map failures, int status, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has status code ').bold().a(status).boldOff().a(' (')
        if (comparison == true) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has status code $status"] = comparison
        }
    }

    void displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
        if (!comparison.isEmpty()) {
            AnsiConsole.out().println('      includes headers')
            Map expectedHeaders = JavaConverters$.MODULE$.mapAsJavaMapConverter(expected.get()).asJava()
            comparison.each { key, headerComparison ->
                def expectedHeaderValue = expectedHeaders[key]
                def ansi = Ansi.ansi().a('        "').bold().a(key).boldOff().a('" with value "').bold()
                    .a(expectedHeaderValue).boldOff().a('" (')
                if (headerComparison == true) {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
                } else {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
                    failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] = headerComparison
                }
            }
        }
    }

    void displayBodyResult(Map failures, String body, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has a matching body').a(' (')
        if (comparison.isEmpty()) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has a matching body"] = comparison
        }
    }

    def stateChange(String state, Consumer consumer) {
        AnsiConsole.out().println(Ansi.ansi().a('  Given ').bold().a(state).boldOff())
        try {
            if (consumer.stateChangeUrl == null) {
                throw new RuntimeException("stateChangeUrl is null")
            }
            def client = new RESTClient(consumer.stateChangeUrl.toString())
            if (consumer.stateChangeUsesBody) {
                client.post(body: [state: state], requestContentType: 'application/json')
            } else {
                client.post(query: [state: state])
            }
            return true
        } catch (e) {
            AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED).a('State Change Request Failed - ')
                .a(e.message).reset())
            if (propertyDefined('pact.showStacktrace')) {
                e.printStackTrace()
            }
            return e
        }
    }

    private boolean propertyDefined(String key) {
        System.getProperty(key) != null || configuration.containsKey(key)
    }

    private boolean filterConsumers(def consumer) {
        !this.propertyDefined('pact.filter.consumers') || consumer.name in this.property('pact.filter.consumers').split(',').collect{
            it.trim()
        }
    }

    private String property(String key) {
        System.getProperty(key, configuration.get(key))
    }

    private boolean filterInteractions(def interaction) {
        if (propertyDefined('pact.filter.description') && propertyDefined('pact.filter.providerState')) {
            matchDescription(interaction) && matchState(interaction)
        } else if (propertyDefined('pact.filter.description')) {
            matchDescription(interaction)
        } else if (propertyDefined('pact.filter.providerState')) {
            matchState(interaction)
        } else {
            true
        }
    }

    private boolean matchState(def interaction) {
        if (interaction.providerState().defined) {
            interaction.providerState().get() ==~ property('pact.filter.providerState')
        } else {
            property('pact.filter.providerState').empty
        }
    }

    private boolean matchDescription(def interaction) {
        interaction.description() ==~ property('pact.filter.description')
    }
}
