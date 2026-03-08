import jenkins.model.Jenkins

// Disable CSRF crumb for learning project (allows Connect → Jenkins REST without crumb)
Jenkins.instance.setCrumbIssuer(null)
Jenkins.instance.save()
println "[init.groovy.d] CSRF crumb issuer disabled"
