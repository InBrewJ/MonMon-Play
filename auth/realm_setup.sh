#!/bin/bash

# All credit to a comment from Thomas in his post:
# https://suedbroecker.net/2020/08/04/how-to-create-a-new-realm-with-the-keycloak-rest-api/

# Set the needed parameter
USER=admin
PASSWORD=admin
GRANT_TYPE=password
CLIENT_ID=admin-cli
INGRESSURL="localhost:8080"

echo "------------------------------------------------------------------------"
echo "Your INGRESSURL for Keycloak: http://$INGRESSURL"
echo "------------------------------------------------------------------------"
echo ""

# Get the bearer token from Keycloak
echo "------------------------------------------------------------------------"
echo "Get the bearer token from Keycloak"
echo "------------------------------------------------------------------------"
echo ""
access_token=$( curl -d "client_id=$CLIENT_ID" -d "username=$USER" -d "password=$PASSWORD" -d "grant_type=$GRANT_TYPE" "http://$INGRESSURL/auth/realms/master/protocol/openid-connect/token" | sed -n 's|.*"access_token":"\([^"]*\)".*|\1|p')

# Create the realm in Keycloak
echo "------------------------------------------------------------------------"
echo "Create the realm in Keycloak"
echo "------------------------------------------------------------------------"
echo ""

result=$(curl -d @./monmon-realm.json -H "Content-Type: application/json" -H "Authorization: bearer $access_token" "http://$INGRESSURL/auth/admin/realms")

if [ "$result" = "" ]; then
echo "------------------------------------------------------------------------"
echo "The realm is created."
echo "Open following link in your browser:"
echo "http://$INGRESSURL/auth/admin/master/console/#/realms/monmon"
echo "------------------------------------------------------------------------"
else
echo "------------------------------------------------------------------------"
echo "It seems there is a problem with the realm creation: $result"
echo "------------------------------------------------------------------------"
fi