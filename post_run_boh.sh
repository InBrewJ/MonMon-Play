echo Waiting for Keycloak to become available and initialising realm...
# Note - this absolutely doesn't work
# Need to find a better way of waiting for the keycloak REST service
# Or, more likely, find a better option to set up realms as a command line arg
# Some more tech debt involves connecting Keycloak to an actual database as well
# as figuring out how to export/import users - see MWM-42
bash -c 'while ! nc -z localhost 8080; do sleep 1; done;' && \
cd auth
./realm_setup.sh
cd -
