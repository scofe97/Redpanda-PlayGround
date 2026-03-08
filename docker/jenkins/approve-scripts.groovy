import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval

def approval = ScriptApproval.get()
approval.preapproveAll()
approval.save()
