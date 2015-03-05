include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'submodule',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: submodule',
    'Gerrit-ApiType: plugin',
    'Gerrit-ApiVersion: 2.10',
    'Gerrit-Module: com.bombardier.transportation.ewea.Module',
    'Gerrit-SshModule: com.bombardier.transportation.ewea.SshModule',
    'Gerrit-HttpModule: com.bombardier.transportation.ewea.HttpModule',
  ],
)

# this is required for bucklets/tools/eclipse/project.py to work
java_library(
  name = 'classpath',
  deps = [':submodule__plugin'],
)

