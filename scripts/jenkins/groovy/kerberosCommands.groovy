/**
 * Returns the cmd used to start H2O in given mode (on Hadoop or standalone). The cmd <strong>must</strong> export
 * the CLOUD_IP and CLOUT_PORT env variables (they are checked afterwards).
 * @param stageConfig stage configuration to read mode and additional information from
 * @return the cmd used to start H2O in given mode
 */
def call(final stageConfig) {
    switch (stageConfig.customData.mode) {
        case H2O_HADOOP_STARTUP_MODE_HADOOP:
            return getCommandHadoop(stageConfig, false)
        case H2O_HADOOP_STARTUP_MODE_HADOOP_SPNEGO:
            return getCommandHadoop(stageConfig, true)
        case H2O_HADOOP_STARTUP_MODE_STANDALONE:
            return getCommandStandalone(stageConfig)
        default:
            error("Startup mode ${stageConfig.customData.mode} for H2O with Hadoop is not supported")
    }
}

private GString getCommandHadoop(final stageConfig, final spnegoAuth) {
    def loginArgs
    def loginEnvs
    if (spnegoAuth) {
        loginArgs = """-spnego_login -user_name ${stageConfig.customData.kerberosUserName} \\
                -login_conf ${stageConfig.customData.spnegoConfigPath} \\
                -spnego_properties ${stageConfig.customData.spnegoPropertiesPath}"""
        loginEnvs = """export KERB_PRINCIPAL=${stageConfig.customData.kerberosPrincipal}"""
    } else {
        loginArgs = "-ldap_login -login_conf ${stageConfig.customData.ldapConfigPath}"
        loginEnvs = ""
    }
    return """
            rm -fv h2o_one_node h2odriver.out
            export HADOOP_CLASSPATH=\$(cat /opt/hive-jdbc-cp)
            hadoop jar h2o-hadoop-*/h2o-${stageConfig.customData.distribution}${stageConfig.customData.version}-assembly/build/libs/h2odriver.jar \\
                -n 1 -mapperXmx 2g -baseport 54445 \\
                -hivePrincipal hive/localhost@H2O.AI -hiveHost localhost:10000 \\
                -jks mykeystore.jks \\
                -notify h2o_one_node -ea -proxy \\
                -jks mykeystore.jks \\
                ${loginArgs} \\
                &> h2odriver.out &
            for i in \$(seq 20); do
              if [ -f 'h2o_one_node' ]; then
                echo "H2O started on \$(cat h2o_one_node)"
                break
              fi
              echo "Waiting for H2O to come up (\$i)..."
              sleep 3
            done
            if [ ! -f 'h2o_one_node' ]; then
              echo 'H2O failed to start!'
              cat h2odriver.out
              exit 1
            fi
            IFS=":" read CLOUD_IP CLOUD_PORT < h2o_one_node
            ${loginEnvs}
            export KRB_USE_TOKEN=true
            export CLOUD_IP=\$CLOUD_IP
            export CLOUD_PORT=\$CLOUD_PORT
        """
}

private GString getCommandStandalone(final stageConfig) {
    def defaultPort = 54321
    return """
            java -Djavax.security.auth.useSubjectCredsOnly=false \\
                -cp build/h2o.jar:\$(cat /opt/hive-jdbc-cp) water.H2OApp \\
                -port ${defaultPort} -ip \$(hostname --ip-address) -name \$(date +%s) \\
                -jks mykeystore.jks \\
                -spnego_login -user_name ${stageConfig.customData.kerberosUserName} \\
                -login_conf ${stageConfig.customData.spnegoConfigPath} \\
                -spnego_properties ${stageConfig.customData.spnegoPropertiesPath} \\
                > standalone_h2o.log 2>&1 & sleep 15
            export KERB_PRINCIPAL=${stageConfig.customData.kerberosPrincipal}
            export CLOUD_IP=\$(hostname --ip-address)
            export CLOUD_PORT=${defaultPort}
        """
}

return this
