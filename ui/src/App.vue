<script setup lang="ts">
import ConnectorTopologyDetails from "./components/ConnectorTopologyDetails.vue";
import ConnectorDetails from "./components/ConnectorDetails.vue";
import { SLOTS } from "@kestra-io/artifact-sdk";
</script>

<template>
    <div style="padding: 1rem; font-family: sans-serif">
        <ConnectorTopologyDetails v-bind="SLOTS['topology-details'].defaultProps" />
        <ConnectorDetails v-bind="SLOTS['execution-artifact'].defaultProps" />
    </div>
</template>