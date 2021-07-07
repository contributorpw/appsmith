import React from "react";
import BaseWidget, { WidgetProps, WidgetState } from "../BaseWidget";
import { WidgetType, RateSize } from "constants/WidgetConstants";
import RateComponent from "components/designSystems/blueprint/RateComponent";
import { ValidationTypes } from "constants/WidgetValidation";
import { DerivedPropertiesMap } from "utils/WidgetFactory";
import * as Sentry from "@sentry/react";
import withMeta, { WithMeta } from "widgets/MetaHOC";
import { EventType } from "constants/AppsmithActionConstants/ActionConstants";
import { isString } from "lodash";

function validateDefaultRate(value: unknown, props: any) {
  let parsed = value;
  let isValid = false;
  if (isString(value as string)) {
    if (/^\d+\.?\d*$/.test(value as string)) {
      parsed = Number(value);
      isValid = true;
    } else {
      return {
        isValid: false,
        parsed: 0,
        message: `Value must be a number`,
      };
    }
  }
  if (Number.isFinite(parsed)) {
    isValid = true;
  }
  // default rate must be less than max count
  if (!isNaN(props.maxCount) && Number(value) > Number(props.maxCount)) {
    return {
      isValid: false,
      parsed,
      message: `This value must be less than or equal to max count`,
    };
  }
  // default rate can be a decimal only if Allow half property is true
  if (!props.isAllowHalf && !Number.isInteger(parsed)) {
    return {
      isValid: false,
      parsed,
      message: `This value can be a decimal only if 'Allow half' is true`,
    };
  }
  return { isValid, parsed };
}

class RateWidget extends BaseWidget<RateWidgetProps, WidgetState> {
  static getPropertyPaneConfig() {
    return [
      {
        sectionName: "General",
        children: [
          {
            propertyName: "maxCount",
            helpText: "Sets the maximum limit of the number of stars",
            label: "Max count",
            controlType: "INPUT_TEXT",
            placeholderText: "Enter max count",
            isBindProperty: true,
            isTriggerProperty: false,
            validation: {
              type: ValidationTypes.NUMBER,
              params: { natural: true },
            },
          },
          {
            propertyName: "defaultRate",
            helpText: "Sets the default number of stars",
            label: "Default rate",
            controlType: "INPUT_TEXT",
            placeholderText: "Enter default value",
            isBindProperty: true,
            isTriggerProperty: false,
            validation: {
              type: ValidationTypes.FUNCTION,
              params: {
                fn: validateDefaultRate,
                expected: { type: Number, example: 5 },
              },
            },
          },
          {
            propertyName: "activeColor",
            label: "Active color",
            controlType: "COLOR_PICKER",
            isBindProperty: false,
            isTriggerProperty: false,
          },
          {
            propertyName: "inactiveColor",
            label: "Inactive color",
            controlType: "COLOR_PICKER",
            isBindProperty: false,
            isTriggerProperty: false,
          },
          {
            propertyName: "tooltips",
            helpText: "Sets the tooltip contents of stars",
            label: "Tooltips",
            controlType: "INPUT_TEXT",
            placeholderText: "Enter tooltips array",
            isBindProperty: true,
            isTriggerProperty: false,
            validation: { type: ValidationTypes.ARRAY },
          },
          {
            propertyName: "size",
            label: "Size",
            controlType: "DROP_DOWN",
            options: [
              {
                label: "Small",
                value: "SMALL",
              },
              {
                label: "Medium",
                value: "MEDIUM",
              },
              {
                label: "Large",
                value: "LARGE",
              },
            ],
            isBindProperty: false,
            isTriggerProperty: false,
          },
          {
            propertyName: "isAllowHalf",
            helpText: "Controls if user can submit half stars",
            label: "Allow half stars",
            controlType: "SWITCH",
            isJSConvertible: true,
            isBindProperty: true,
            isTriggerProperty: false,
            validation: { type: ValidationTypes.BOOLEAN },
          },
          {
            propertyName: "isVisible",
            helpText: "Controls the visibility of the widget",
            label: "Visible",
            controlType: "SWITCH",
            isJSConvertible: true,
            isBindProperty: true,
            isTriggerProperty: false,
            validation: { type: ValidationTypes.BOOLEAN },
          },
          {
            propertyName: "isDisabled",
            helpText: "Disables input to the widget",
            label: "Disabled",
            controlType: "SWITCH",
            isJSConvertible: true,
            isBindProperty: true,
            isTriggerProperty: false,
            validation: { type: ValidationTypes.BOOLEAN },
          },
        ],
      },
      {
        sectionName: "Actions",
        children: [
          {
            helpText: "Triggers an action when the rate is changed",
            propertyName: "onRateChanged",
            label: "onChange",
            controlType: "ACTION_SELECTOR",
            isJSConvertible: true,
            isBindProperty: true,
            isTriggerProperty: true,
          },
        ],
      },
    ];
  }

  static getDefaultPropertiesMap(): Record<string, string> {
    return {
      rate: "defaultRate",
    };
  }

  static getDerivedPropertiesMap(): DerivedPropertiesMap {
    return {
      value: `{{ this.rate }}`,
    };
  }

  static getMetaPropertiesMap(): Record<string, any> {
    return {
      rate: undefined,
    };
  }

  valueChangedHandler = (value: number) => {
    this.props.updateWidgetMetaProperty("rate", value, {
      triggerPropertyName: "onRateChanged",
      dynamicString: this.props.onRateChanged,
      event: {
        type: EventType.ON_RATE_CHANGED,
      },
    });
  };

  getPageView() {
    return (
      (this.props.rate || this.props.rate === 0) && (
        <RateComponent
          key={this.props.widgetId}
          onValueChanged={this.valueChangedHandler}
          readonly={this.props.isDisabled}
          value={this.props.rate}
          {...this.props}
        />
      )
    );
  }

  getWidgetType(): WidgetType {
    return "RATE_WIDGET";
  }
}

export interface RateWidgetProps extends WidgetProps, WithMeta {
  maxCount: number;
  size: RateSize;
  defaultRate?: number;
  rate?: number;
  activeColor?: string;
  inactiveColor?: string;
  isAllowHalf?: boolean;
  onRateChanged?: string;
  tooltips?: Array<string>;
}

export default RateWidget;
export const ProfiledRateWidget = Sentry.withProfiler(withMeta(RateWidget));
